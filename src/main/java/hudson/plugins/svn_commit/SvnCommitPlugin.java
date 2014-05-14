package hudson.plugins.svn_commit;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.EnvVars;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.remoting.VirtualChannel;
import hudson.scm.SvnClientManager;
import hudson.scm.SubversionSCM;
import hudson.scm.SubversionSCM.ModuleLocation;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.wc.SVNCommitClient;


public class SvnCommitPlugin {
	
    private SvnCommitPlugin() {
    }
    
    private static class CommitTask implements FileCallable<Boolean> {
    	
		private static final long serialVersionUID = 2L;
		private BuildListener buildListener;
		private ModuleLocation moduleLocation;
		private ISVNAuthenticationProvider authProvider;
		private String commitMessage;

		public CommitTask(AbstractProject<?, ?> rootProject, ModuleLocation moduleLocation, SubversionSCM scm, BuildListener buildListener, String commitMessage) {
			this.moduleLocation = moduleLocation;
			this.commitMessage = commitMessage;
			this.authProvider = scm.createAuthenticationProvider(rootProject, moduleLocation);
			this.buildListener = buildListener;
		}

		public Boolean invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
			SvnClientManager svnClientManager = null;
			
			try {
				File moduleDirectory = new File(workspace, moduleLocation.getLocalDir());
				buildListener.getLogger().println(Messages.Committing(moduleDirectory));
				svnClientManager = SubversionSCM.createClientManager(authProvider);			
				SVNCommitClient commitClient = svnClientManager.getCommitClient();
				SVNCommitInfo commitInfo = commitClient.doCommit(new File[]{moduleDirectory}, false, commitMessage, null, null, false, false,SVNDepth.INFINITY);
			    SVNErrorMessage errorMsg = commitInfo.getErrorMessage();
	
	            if (null != errorMsg) {
	                buildListener.getLogger().println(Messages.CommitFailed(errorMsg.getFullMessage()));
	                return false;
	            } else if (commitInfo.getNewRevision() < 0) {
	            	buildListener.getLogger().println(Messages.NothingToCommit(moduleLocation));
	            } else {
	            	buildListener.getLogger().println(Messages.Committed(moduleLocation, commitInfo.getNewRevision()));
	            }
			}catch (SVNException e) {
				buildListener.getLogger().println(Messages.CommitFailed(e.getLocalizedMessage()));
				return false;
			} finally {
				if (svnClientManager != null) {
					svnClientManager.dispose();
				}
			}
			return true;
		}
    	
    }

    /**
     *
     * @param build build
     * @param launcher      launcher
     * @param buildListener build listener
     * @param commitComment    commit comment
     * @return true if the operation was successful
     * @throws InterruptedException 
     * @throws IOException 
     */
    public static boolean perform(AbstractBuild<?,?> build,
                                  Launcher launcher,
                                  BuildListener buildListener,
                                  String commitComment) throws IOException, InterruptedException {
        PrintStream logger = buildListener.getLogger();

        if (Result.SUCCESS!=build.getResult()) {
            logger.println(Messages.UnsuccessfulBuild());
            return true;
        }

        // in the presence of Maven module build and promoted builds plugin (JENKINS-5608),
        // we rely on the root project to find the SCM configuration
        final AbstractProject<?, ?> rootProject = build.getProject().getRootProject();
        final AbstractBuild<?, ?> rootBuild = build.getRootBuild();

        if (!(rootProject.getScm() instanceof SubversionSCM)) {
            logger.println(Messages.NotSubversion(rootProject.getScm().toString()));
            return true;
        }

        SubversionSCM scm = SubversionSCM.class.cast(rootProject.getScm());
        EnvVars envVars = rootBuild.getEnvironment(buildListener);

        scm.buildEnvVars(rootBuild, envVars);

        String evalComment = evalGroovyExpression(envVars, commitComment);
        Boolean result = true;
        
        
        ModuleLocation[] moduleLocations = scm.getLocations(envVars, rootBuild);
		for (SubversionSCM.ModuleLocation moduleLocation : moduleLocations) {
	        	CommitTask commitTask = new CommitTask(rootProject, moduleLocation, scm, buildListener, evalComment);
				result &= rootBuild.getWorkspace().act(commitTask);
        }

        return result;
    }

    static String evalGroovyExpression(Map<String, String> env, String evalText) {
        Binding binding = new Binding();
        binding.setVariable("env", env);
        binding.setVariable("sys", System.getProperties());
        CompilerConfiguration config = new CompilerConfiguration();
        GroovyShell shell = new GroovyShell(binding, config);
        Object result = shell.evaluate("return \"" + evalText + "\"");
        if (result == null) {
            return "";
        } else {
            return result.toString().trim();
        }
    }
}
