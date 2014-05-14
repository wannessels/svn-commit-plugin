package hudson.plugins.svn_commit;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;

import java.io.IOException;
import java.util.HashMap;

import net.sf.json.JSONObject;

import org.codehaus.groovy.control.CompilationFailedException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;


/**
 * Performs <tt>svn copy</tt> when the build was successfully done. Note that
 * this plugin is executed after the build state is finalized, and the errors
 * happened in this plugin doesn't affect to the state of the build.
 *
 * @author Kenji Nakamura
 */
@SuppressWarnings({"PublicMethodNotExposedInInterface"})
public class SvnCommitPublisher extends Recorder {

    private String commitComment = null;

    @DataBoundConstructor
    public SvnCommitPublisher(String commitComment) {
        this.commitComment = commitComment;
    }


    public String getCommitComment() {
        return this.commitComment;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> abstractBuild, Launcher launcher, BuildListener buildListener) throws InterruptedException, IOException {
        return SvnCommitPlugin.perform(abstractBuild, launcher, buildListener, this.getCommitComment());
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        return false;
    }

    /**
     * Returns the descriptor value.
     *
     * @return the descriptor value.
     */
    @Override
    public SvnCommitDescriptorImpl getDescriptor() {
        return (SvnCommitDescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class SvnCommitDescriptorImpl extends BuildStepDescriptor<Publisher> {


        private String commitComment;

        /**
         * Creates a new SvnCommitDescriptorImpl object.
         */
        public SvnCommitDescriptorImpl() {
            this.commitComment = Messages.DefaultTagComment();
            load();
        }

        /**
         * Returns the display name value.
         *
         * @return the display name value.
         */
        @Override
        public String getDisplayName() {
            return Messages.DisplayName();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();

            return super.configure(req, formData);
        }

    
        /**
         * Returns the commit comment value.
         *
         * @return the commit comment value.
         */
        public String getCommitComment() {
            return this.commitComment;
        }

        /**
         * Sets the value of commit comment.
         *
         * @param commitComment the commit comment value.
         */
        public void setCommitComment(String tagComment) {
            this.commitComment = tagComment;
        }

        public FormValidation doCheckCommitComment(@QueryParameter final String value) {
            try {
                SvnCommitPlugin.evalGroovyExpression(new HashMap<String, String>(), value);
                return FormValidation.ok();
            } catch (CompilationFailedException e) {
                return FormValidation.error(Messages.BadGroovy(e.getMessage()));
            }
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

    }
}
