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
import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import hudson.scm.SvnClientManager;
import hudson.scm.SubversionSCM;
import hudson.scm.SubversionSCM.ModuleLocation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.wc.SVNCommitClient;
import org.tmatesoft.svn.core.wc.SVNWCUtil;


/**
 * Consolidates the work common in Publisher and MavenReporter.
 *
 * @author Kenji Nakamura
 */
@SuppressWarnings(
        {"UtilityClass", "ImplicitCallToSuper", "MethodReturnOfConcreteClass",
                "MethodParameterOfConcreteClass", "InstanceofInterfaces"})
public class SvnCommitPlugin {

    /**
     * Creates a new SvnTagPlugin object.
     */
    private SvnCommitPlugin() {
    }
    
    private static class CommitTask implements FileCallable<Boolean> {
    	
		private static final long serialVersionUID = 1L;
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
				svnClientManager = SubversionSCM.createClientManager(authProvider);			
				SVNCommitInfo commitInfo = svnClientManager.getCommitClient().doCommit(new File[]{moduleDirectory}, false, commitMessage, null, null, false, false,SVNDepth.INFINITY);
			    SVNErrorMessage errorMsg = commitInfo.getErrorMessage();
	
	            if (null != errorMsg) {
	                buildListener.getLogger().println(Messages.CommitFailed(errorMsg.getFullMessage()));
	                return false;
	            } else {
	            	buildListener.getLogger().println(Messages.Committed(commitInfo.getNewRevision()));
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
     * True if the operation was successful.
     *
     * @param build build
     * @param launcher      launcher
     * @param buildListener build listener
     * @param commitComment    commit comment
     * @return true if the operation was successful
     * @throws InterruptedException 
     * @throws IOException 
     */
    @SuppressWarnings({"FeatureEnvy", "UnusedDeclaration", "TypeMayBeWeakened", "LocalVariableOfConcreteClass"})
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
        // we rely on the root project to find the SCM configuration and revision to tag.
        final AbstractProject<?, ?> rootProject = build.getProject().getRootProject();
        final AbstractBuild<?, ?> rootBuild = build.getRootBuild();

        if (!(rootProject.getScm() instanceof SubversionSCM)) {
            logger.println(Messages.NotSubversion(rootProject.getScm().toString()));
            return true;
        }

        SubversionSCM scm = SubversionSCM.class.cast(rootProject.getScm());
        EnvVars envVars = rootBuild.getEnvironment(buildListener);

        // Let SubversionSCM fill revision number.
        // It is guaranteed for getBuilds() return the latest build (i.e.
        // current build) at first
        // The passed in abstractBuild may be the sub maven module and not
        // have revision.txt holding Svn revision information, so need to use
        // the build associated with the root level project.
        scm.buildEnvVars(rootBuild, envVars);

        String evalComment = evalGroovyExpression(envVars, commitComment);
        Boolean result = true;
        
        for (SubversionSCM.ModuleLocation moduleLocation : scm.getLocations(envVars, rootBuild)) {
	        	ISVNAuthenticationProvider sap = scm.createAuthenticationProvider(rootProject, moduleLocation);
	            if (sap == null) {
	                logger.println(Messages.NoSVNAuthProvider());
	                return false;
	            }
	        	CommitTask commitTask = new CommitTask(rootProject, moduleLocation, scm, buildListener, evalComment);
				result &= rootBuild.getWorkspace().act(commitTask);
        }

        return result;
    }

    @SuppressWarnings({"StaticMethodOnlyUsedInOneClass", "TypeMayBeWeakened"})
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


    /**
     * Reads the revision file of the specified build.
     *
     * @param build build object
     * @return map from Subversion URL to its revision.
     * @throws java.io.IOException thrown when operation failed
     */
    /*package*/
    @SuppressWarnings({"NestedAssignment"})
    static Map<String, Long> parseRevisionFile(AbstractBuild build) throws IOException {
        Map<String, Long> revisions = new HashMap<String, Long>(); // module -> revision
        // read the revision file of the last build
        File file = SubversionSCM.getRevisionFile(build);
        if (!file.exists()) // nothing to compare against
        {
            return revisions;
        }

        BufferedReader br = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                int index = line.lastIndexOf('/');
                if (index < 0) {
                    continue;   // invalid line?
                }
                try {
                	revisions.put(SVNURL.parseURIEncoded(line.substring(0, index)).toString(),
                			Long.parseLong(line.substring(index + 1)));
                } catch (NumberFormatException e) {
                    // perhaps a corrupted line. ignore
                } catch(SVNException e) {
                	// perhaps a corrupted line. ignore
                }
            }
        } finally {
            br.close();
        }

        return revisions;
    }
}
