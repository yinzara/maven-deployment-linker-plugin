package hudson.plugins.mavendeploymentlinker;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.PermalinkProjectAction.Permalink;
import hudson.model.Run;
import hudson.plugins.mavendeploymentlinker.MavenDeploymentLinkerAction.ArtifactVersion;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.IOUtils;
import hudson.util.Secret;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPassword;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Realm;
import com.ning.http.client.Realm.AuthScheme;
import com.ning.http.client.Response;

/**
 * This builder is able to resolve the linked maven artifacts on other projects
 * and use the information to download the deployed artifacts to the local
 * workspace. This allows to save space on the master, by not having to archive
 * the artifacts for the copyartifact plugin.
 *
 * @author Dominik Bartholdi (imod)
 *
 */
public class MavenDeploymentDownloader extends Builder {

    private static final String BUILD_NUMBER_LINK_NAME = "Build Number";
    private static final String BUILD_NUMBER_LINK_ID = "buildNumber";

    private static final String LAST_COMPLETED_BUILD_NAME = "Last Completed Build";
    private static final String LAST_COMPLETED_BUILD_ID = "lastCompletedBuild";

    private final String projectName;
    private final String filePattern;
    private final String targetDir;
    private final boolean stripVersion;
    private final boolean failIfNoArtifact;
    private final boolean cleanTargetDir;
    private final String stripVersionPattern;
    private final String permaLink;
    private transient Pattern filePatternMatcher;
    private final String buildNumber;
    private final String credentialsId;

    public static class AuthenticationBlock {

        private final String credentialsId;

        @DataBoundConstructor
        public AuthenticationBlock(String credentialsId) {
            this.credentialsId = credentialsId;
        }

        public String getCredentialsId() {
            return credentialsId;
        }
    }

    /**
     *
     * @param projectName
     *            the name of the project to copy the artifacts from
     * @param filePattern
     *            the pattern to find the files to be copied
     * @param permaLink
     *            the link to the specific build to copy the artifacts from
     * @param targetDir
     *            where to copy the artifacts to
     * @param stripVersion
     *            strip the version of the files
     * @param stripVersionPattern
     *            overwrite the strip pattern
     * @param failIfNoArtifact
     *            fail if there was no artifact to copy
     * @param cleanTargetDir
     *            remove the content of the target directory before copying the
     *            new files?
     */
    @DataBoundConstructor
    public MavenDeploymentDownloader(String projectName, String filePattern, String permaLink, String targetDir,
                    boolean stripVersion, String stripVersionPattern, boolean failIfNoArtifact, boolean cleanTargetDir,
                    String buildNumber, AuthenticationBlock authenticationBlock) {
        // check the permissions only if we can
        if (!projectName.startsWith("$")) { // if this is a parameter, we can't
                                            // check the name here it will be
                                            // expanded by the TokenMacro...
            StaplerRequest req = Stapler.getCurrentRequest();
            if (req != null) {
                // Prevents both invalid values and access to artifacts of
                // projects which this user cannot see.
                // If value is parameterized, it will be checked when build
                // runs.
                if (Hudson.getInstance().getItemByFullName(projectName, Job.class) == null) {
                    projectName = ""; // Ignore/clear bad value to avoid ugly
                                      // 500 page
                }
            }
        }
        this.projectName = projectName;
        this.filePattern = filePattern;
        this.targetDir = targetDir;
        this.stripVersion = stripVersion;
        this.permaLink = permaLink;
        this.failIfNoArtifact = failIfNoArtifact;
        this.cleanTargetDir = cleanTargetDir;
        this.stripVersionPattern = Util.fixEmpty(stripVersionPattern);
        this.buildNumber = buildNumber;

        if (authenticationBlock != null) {
            this.credentialsId = authenticationBlock.getCredentialsId();
        } else {
            this.credentialsId = null;
        }

    }

    private Pattern getFilePatternMatcher() {
        if (filePatternMatcher == null) {
            this.filePatternMatcher = filePattern == null ? Pattern.compile(".*") : Pattern.compile(filePattern);
        }
        return filePatternMatcher;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePattern() {
        return filePattern;
    }

    public String getTargetDir() {
        return targetDir;
    }

    public boolean isStripVersion() {
        return stripVersion;
    }

    public boolean isCleanTargetDir() {
        return cleanTargetDir;
    }

    public String getStripVersionPattern() {
        return stripVersionPattern;
    }

    public String getPermaLink() {
        return permaLink;
    }

    public boolean isFailIfNoArtifact() {
        return failIfNoArtifact;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public void logResolvedArtifact(String resolvedProjectName, Run<?, ?> resolvedJob, String linkId, String linkName,
                    PrintStream console) {
        // do some hyper linked logging
        final String rootUrl = StringUtils.defaultIfBlank(Hudson.getInstance().getRootUrl(), "/");
        final String jobUrl = rootUrl + "job/" + resolvedProjectName;
        final String linkBuildNr = HyperlinkNote.encodeTo(jobUrl + "/" + resolvedJob.number, "#" + resolvedJob.number);
        final String linkPerma = HyperlinkNote.encodeTo(jobUrl + "/" + linkId, linkName);
        final String linkJob = HyperlinkNote.encodeTo(jobUrl, resolvedProjectName);

        console.println(Messages.resolveArtifact(linkBuildNr, linkPerma, linkJob));
    }

    private SSHUserPassword lookupCredentials() {
        if (credentialsId != null) {
            final List<SSHUserPassword> credentialsList = CredentialsProvider.lookupCredentials(SSHUserPassword.class,
                            Hudson.getInstance(), ACL.SYSTEM);
            for (final SSHUserPassword credentials : credentialsList) {
                if (credentials.getId().equals(credentialsId)) {
                    return credentials;
                }
            }
        }

        return null;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, IOException {

        final PrintStream console = listener.getLogger();

        String resolvedProjectName = null;
        try {
            resolvedProjectName = TokenMacro.expandAll(build, listener, projectName);
        } catch (MacroEvaluationException e1) {
            console.println(Messages.jobNameExandFailed() + ": " + e1.getMessage());
            return false;
        }

        if (StringUtils.isBlank(resolvedProjectName)) {
            console.println(Messages.noJobName());
            return false;
        }

        final Job<?, ?> job = Hudson.getInstance().getItemByFullName(resolvedProjectName, Job.class);

        final EnvVars envVars = build.getEnvironment(listener);
        final String expandedTargetDir = envVars.expand(targetDir);

        FilePath targetDirFp = new FilePath(build.getWorkspace(), expandedTargetDir);
        if (cleanTargetDir) {
            console.println("deleting content of " + targetDirFp.getRemote());
            targetDirFp.deleteContents();
        }
        List<MavenDeploymentLinkerAction> linkerActions = Collections.emptyList();

        if (BUILD_NUMBER_LINK_ID.equals(permaLink)) {
            final String expandedBuildNumber = envVars.expand(buildNumber);

            if (!StringUtils.isNumeric(expandedBuildNumber)) {
                console.println(Messages.notANumber(expandedBuildNumber, buildNumber));
            } else {
                final Run<?, ?> resolvedJob = job.getBuildByNumber(Integer.valueOf(expandedBuildNumber));
                if (resolvedJob == null) {
                    console.println(Messages.buildDoesNotExist(expandedBuildNumber, job.getName()));
                } else {
                    linkerActions = resolvedJob.getActions(MavenDeploymentLinkerAction.class);
                    logResolvedArtifact(resolvedProjectName, resolvedJob, BUILD_NUMBER_LINK_ID, BUILD_NUMBER_LINK_NAME,
                                    console);
                }
            }
        } else if (LAST_COMPLETED_BUILD_ID.equals(permaLink)) {
            final Run<?, ?> resolvedJob = job.getLastCompletedBuild();

            if (resolvedJob == null) {
                console.println(Messages.buildDoesNotExist(LAST_COMPLETED_BUILD_NAME, job.getName()));
            } else {
                linkerActions = resolvedJob.getActions(MavenDeploymentLinkerAction.class);
                logResolvedArtifact(resolvedProjectName, resolvedJob, LAST_COMPLETED_BUILD_ID,
                                LAST_COMPLETED_BUILD_NAME, console);
            }

        } else {
            for (Permalink link : job.getPermalinks()) {
                if (link.getId().equals(permaLink)) {
                    final Run<?, ?> resolvedJob = link.resolve(job);
                    linkerActions = resolvedJob.getActions(MavenDeploymentLinkerAction.class);

                    logResolvedArtifact(resolvedProjectName, resolvedJob, link.getId(), link.getDisplayName(), console);
                }
            }
        }

        int matchedFiles = 0;
        for (MavenDeploymentLinkerAction action : linkerActions) {
            final List<ArtifactVersion> deployments = action.getDeployments();
            for (ArtifactVersion av : deployments) {
                String url = av.getUrl();
                if (StringUtils.isNotBlank(url) && url.startsWith("http")) {
                    String fileName = "n/a";
                    try {
                        // do some basic validation on the url
                        URL u = new URL(url);
                        fileName = getFileName(u.getPath(), isStripVersion());
                    } catch (Exception e) {
                        console.println(Messages.failedUrlParsing(url, e.getMessage()));
                        // fall back to simple substitution
                        fileName = getFileName(url, isStripVersion());
                    }
                    // only download files matching the given file pattern
                    if (getFilePatternMatcher().matcher(fileName).matches()) {
                        matchedFiles++;
                        FilePath fp = new FilePath(targetDirFp, fileName);
                        console.println(Messages.downloadArtifact(HyperlinkNote.encodeTo(url, url), fp.getRemote()));

                        SSHUserPassword credentials = lookupCredentials();

                        AsyncHttpClient client;
                        if (credentials == null) {
                            client = new AsyncHttpClient();
                        } else {
                            AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder();
                            Realm realm = new Realm.RealmBuilder().setPrincipal(credentials.getUsername())
                                            .setPassword(Secret.toString(credentials.getPassword()))
                                            .setUsePreemptiveAuth(true).setScheme(AuthScheme.BASIC).build();
                            builder.setRealm(realm).build();
                            client = new AsyncHttpClient(builder.build());
                        }

                        try {
                            if (!downloadFile(client, url, fp, console)) {
                                return false;
                            }
                        } catch (ExecutionException e) {
                            console.println(Messages.downloadArtifactFailed(HyperlinkNote.encodeTo(url, url),
                                            e.getMessage()));
                            throw new IOException(Messages.downloadArtifactFailed(url, e.getMessage()), e);
                        }
                    }
                }
            }
        }
        if (matchedFiles == 0) {
            if (failIfNoArtifact) {
                console.println(Messages.noArtifactFoundError(filePattern));
                return false;
            } else {
                console.println(Messages.noArtifactFoundWarning(filePattern));
            }
        }

        return true;
    }

    private String getFileName(String url, boolean stripVersion) {
        int slashIndex = url.lastIndexOf('/');
        String fname = url.substring(slashIndex + 1);
        if (stripVersion) {
            fname = stripVersionPattern == null ? VersionUtil.stripeVersion(fname) : VersionUtil.stripeVersion(fname,
                            stripVersionPattern);
        }
        return fname;
    }

    private boolean downloadFile(AsyncHttpClient client, String url, FilePath target, PrintStream console)
                    throws InterruptedException, ExecutionException, IOException {
        final Response response = client.prepareGet(url).execute().get();

        if (response.getStatusCode() == 200) {
            final InputStream is = response.getResponseBodyAsStream();
            IOUtils.copy(is, target.write());// don't close stream...
            return true;
        }

        console.println(Messages.downloadArtifactFailedHttp(url, response.getStatusCode(), response.getStatusText()));
        return false;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public AutoCompletionCandidates doAutoCompleteProjectName(@QueryParameter String value) {
            final List<Job> jobs = Hudson.getInstance().getItems(Job.class);
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            for (Job<?, ?> job : jobs)
                if (job.getName().toLowerCase().startsWith(value.toLowerCase()))
                    c.add(job.getName());
            return c;
        }

        public ListBoxModel doFillCredentialsIdItems() {
            ListBoxModel m = new ListBoxModel();

            for (SSHUserPassword u : CredentialsProvider.lookupCredentials(SSHUserPassword.class, Hudson.getInstance(),
                            ACL.SYSTEM)) {
                m.add(u.getUsername()
                                + (StringUtils.isNotEmpty(u.getDescription()) ? " (" + u.getDescription() + ")" : ""),
                                u.getId());
            }

            return m;
        }

        public ListBoxModel doFillPermaLinkItems(@AncestorInPath Job<?, ?> defaultJob,
                        @QueryParameter("projectName") String projectName) {
            // gracefully fall back to some job, if none is given
            Job<?, ?> j = null;
            if (projectName != null)
                j = Hudson.getInstance().getItem(projectName, defaultJob, Job.class);
            if (j == null)
                j = defaultJob;

            ListBoxModel r = new ListBoxModel();
            for (Permalink p : j.getPermalinks()) {
                r.add(new Option(p.getDisplayName(), p.getId()));
            }
            // also add buildNumber and lastCompetedBuild
            r.add(new Option(LAST_COMPLETED_BUILD_NAME, LAST_COMPLETED_BUILD_ID));
            r.add(new Option(BUILD_NUMBER_LINK_NAME, BUILD_NUMBER_LINK_ID));

            return r;
        }

        /**
         * checks the file pattern to find the files we have to download.
         */
        public FormValidation doCheckFilePattern(@QueryParameter String value) throws IOException, ServletException {
            String pattern = Util.fixEmptyAndTrim(value);
            if (pattern == null) {
                return FormValidation.error(Messages.FilePatternRequired());
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                return FormValidation.error(Messages.FilePatternInvalidSyntax());
            }
            return FormValidation.ok();
        }

        /**
         * checks the pattern used to strip the version of the file name - this
         * is optional, as we have a default.
         *
         * @see VersionUtil#SNAPSHOT_FILE_PATTERN_STR
         * @see VersionUtil#VERSION_FILE_PATTERN_STR
         */
        public FormValidation doCheckStripVersionPattern(@QueryParameter String value) throws IOException,
                        ServletException {
            String pattern = Util.fixEmptyAndTrim(value);
            if (pattern != null) {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException e) {
                    return FormValidation.error(Messages.StripVersionPatternInvalidSyntax());
                }
            }
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.MavenDeploymentDownloader_DisplayName();
        }

    }
}
