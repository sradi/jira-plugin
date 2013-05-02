package hudson.plugins.jira;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.plugins.jira.soap.RemoteComponent;
import hudson.plugins.jira.soap.RemoteIssue;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.*;
import hudson.util.FormValidation;

import javax.xml.rpc.ServiceException;
import java.io.*;
import java.util.HashMap;

/**
 * <p>
 *  When a build fails it creates jira issues.
 *  Repeated failures does not create a new issue but update the existing issue untill the issue is closed.
 *
 * @author Rupali Behera
 * @email rupali@vertisinfotech.com
 *
 */
public class JiraCreateIssueNotifier extends Notifier {

    private String projectKey;
    private String testDescription;
    private String assignee;
    private String component;

    @DataBoundConstructor
    public JiraCreateIssueNotifier(String projectKey,String testDescription,String assignee,
                                   String component) {
        if (projectKey == null) throw new IllegalArgumentException("Project key cannot be null");
        this.projectKey = projectKey;

        this.testDescription=testDescription;
        this.assignee=assignee;
        this.component=component;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getTestDescription() {
        return testDescription;
    }

    public void setTestDescription(String testDescription) {
        this.testDescription = testDescription;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }
    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws IOException {

        String jobDirPath=Jenkins.getInstance().getBuildDirFor(build.getProject()).getPath();
        String filename=jobDirPath+"/"+"issue.txt";

        try {
            EnvVars environmentVariable = build.getEnvironment(TaskListener.NULL);

            Result currentBuildResult= build.getResult();
            System.out.println("current result::"+currentBuildResult);

            Result previousBuildResult=null;
            AbstractBuild previousBuild=build.getPreviousBuild();

            if (previousBuild!=null) {
                previousBuildResult= previousBuild.getResult();
                System.out.println("previous result::"+previousBuildResult);
            }

            if (currentBuildResult!=Result.ABORTED && previousBuild!=null) {
                if (currentBuildResult==Result.FAILURE) {
                    currentBuildResultFailure(build,listener,previousBuildResult,filename,environmentVariable);
                }

                if (currentBuildResult==Result.SUCCESS) {
                    currentBuildResultSuccess(build,previousBuildResult,filename,environmentVariable);
                }
            }
       } catch(InterruptedException e) {
           System.out.print("Build is aborted..!!!");
       }
       return true;
    }

    /**
     * It creates a issue in the given project, with the given description, assignee,components and summary.
     * The created issue ID is saved to the file at "filename".
     *
     * @param build
     * @param filename
     * @return issue id
     * @throws ServiceException
     * @throws IOException
     * @throws InterruptedException
     */
    public RemoteIssue createJiraIssue(AbstractBuild<?, ?> build,String filename) throws ServiceException,
            IOException,InterruptedException {

        EnvVars environmentVariable = build.getEnvironment(TaskListener.NULL);
        String checkDescription;
        RemoteComponent components[]=null;
        String buildURL=environmentVariable.get("BUILD_URL");
        String buildNumber=environmentVariable.get("BUILD_NUMBER");
        String jobName=environmentVariable.get("JOB_NAME");
        String jenkinsURL=Jenkins.getInstance().getRootUrl();

        checkDescription=(this.testDescription=="") ? "No description is provided" : this.testDescription;
        String description="The test "+jobName+" has failed."+"\n\n"+checkDescription+"\n\n"+
                "* First failed run : ["+buildNumber+"|"+buildURL+"]"+"\n"+ "** [console log|"+
                buildURL.concat("console")+"]"+"\n\n\n\n"+"If it is false alert please notify to QA tools :"
                +"\n"+"# Move to the OTA project and"+"\n"
                +"# Set the component to Tools-Jenkins-Jira Integration.";

        String assignee = (this.assignee=="") ? "" : this.assignee;

        if (this.component=="") {
            components=null;
        } else{
            components=getComponent(build,this.component);
        }

        String summary="Test "+jobName+" failure - "+jenkinsURL;

        JiraSession session = getJiraSession(build);
        RemoteIssue issue = session.createIssue(projectKey,description,assignee,components,summary);

        //writing the issue-id to the file, which is present in job's directory.
        writeInFile(filename,issue);
        return issue;
    }

    /**
     * Returns the status of the issue.
     *
     * @param build
     * @param id
     * @return Status of the issue
     * @throws ServiceException
     * @throws IOException
     */
    public String getStatus(AbstractBuild<?, ?> build,String id) throws ServiceException,IOException {

        JiraSession session = getJiraSession(build);
        RemoteIssue issue=session.getIssueByKey(id);
        String status=issue.getStatus();
        return status;
    }

    /**
     *  Adds a comment to the existing issue.
     *
     * @param build
     * @param id
     * @param comment
     * @throws ServiceException
     * @throws IOException
     */
    public void addComment(AbstractBuild<?, ?> build,String id,String comment)
            throws ServiceException,IOException {

        JiraSession session = getJiraSession(build);
        session.addCommentWithoutConstrains(id,comment);
    }

    /**
     * Returns an Array of componets given by the user
     *
     * @param build
     * @param component
     * @return
     * @throws ServiceException
     * @throws IOException
     */
    public RemoteComponent[] getComponent(AbstractBuild<?, ?> build,String component) throws
            ServiceException,IOException {

        JiraSession session = getJiraSession(build);
        RemoteComponent availableComponents[]= session.getComponents(projectKey);

        //To store all the componets of the particular project
        HashMap<String,String> components=new HashMap<String, String>();

        //converting the user input as a string array
        String inputComponents[]=component.split(",");
        int numberOfComponents=inputComponents.length;
        RemoteComponent allcomponents[]=new RemoteComponent[numberOfComponents];
        for (RemoteComponent rc:availableComponents) {
            String name=rc.getName();
            String id=rc.getId();
            components.put(name,id);
        }
        int i=0;
        while (i<numberOfComponents) {
            RemoteComponent componentIssue=new RemoteComponent();
            String userInput= inputComponents[i];
            String id="";
            for (String key:components.keySet()) {
                if (userInput.equalsIgnoreCase(key)) {
                    id=components.get(key);
                }
            }
            componentIssue.setName(userInput);
            componentIssue.setId(id);
            allcomponents[i]=componentIssue;
            i++;
        }
       return allcomponents;
    }

    /**
     * Returns the issue id
     *
     * @param filename
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    public String getIssue(String filename) throws IOException,InterruptedException {

        String issueId="";
        try {
            BufferedReader br = null;
            String issue;
            br = new BufferedReader(new FileReader(filename));

            while ((issue = br.readLine()) != null) {
                issueId=issue;
            }
            br.close();
            return issueId;
        } catch(FileNotFoundException e) {
            System.out.println("There is no such file...!!");
            return null;
        }

    }

    /**
     * Returns the jira session.
     *
     * @param build
     * @return JiraSession
     * @throws ServiceException
     * @throws IOException
     */
    public JiraSession getJiraSession(AbstractBuild<?, ?> build) throws ServiceException,IOException {

        JiraSite site = JiraSite.get(build.getProject());
        if (site==null)  throw new IllegalStateException("JIRA site needs to be configured in the project "
                + build.getFullDisplayName());

        JiraSession session = site.createSession();
        if (session==null)  throw new IllegalStateException("Remote SOAP access for JIRA isn't " +
                "configured in Jenkins");

        return session;
    }

    /**
     * @param filename
     */
    public void deleteFile(String filename) {
        File file=new File(filename);
        if (file.exists()) {
            if (file.delete()) {
                System.out.println("File deleted successfully...!!!");
            } else {
                System.out.println("File do not deleted :( ...!!!");
            }
        }
    }

    /**
     * write's the issue id in the file, which is stored in the Job's directory
     * @param Filename
     * @param issue
     * @throws FileNotFoundException
     */
    public void writeInFile(String Filename,RemoteIssue issue) throws FileNotFoundException {
        PrintWriter writer = new PrintWriter(Filename);
        writer.println(issue.getKey());
        writer.close();
    }

    /**
     * when the current build fails it checks for the previous build's result,
     * creates jira issue if the result was "success" and adds comment if the result was "fail".
     * It adds comment until the previously created issue is closed.
     *
     */
    public void currentBuildResultFailure(AbstractBuild<?, ?> build,BuildListener listener,
                    Result previousBuildResult,String filename,EnvVars environmentVariable)
                throws InterruptedException,IOException {

        String buildURL=environmentVariable.get("BUILD_URL");
        String buildNumber=environmentVariable.get("BUILD_NUMBER");
        if (previousBuildResult==Result.FAILURE) {
            String comment="- Job is still failing."+"\n"+"- Failed run : ["+
                    buildNumber+"|"+buildURL+"]"+"\n"+ "** [console log|"+buildURL.concat("console")+"]";
            //Get the issue-id which was filed when the previous built failed
            String issueId=getIssue(filename);
            if (issueId!=null) {
                listener.getLogger().println("*************************Test fails again****************"+
                        "**************");
                try {
                    //The status of the issue which was filed when the previous build failed
                    String Status=getStatus(build,issueId);

                    //Status=1=Open OR Status=5=Resolved
                    if (Status.equals("1")||Status.equals("5")) {
                        listener.getLogger().println("The previous build also failed creating issue with "+
                                "issue ID"+" "+issueId);
                        addComment(build,issueId,comment);
                    }

                    if (Status.equals("6")) {
                        listener.getLogger().println("The previous build also failed but the issue " +
                                "is closed");
                        deleteFile(filename);
                        RemoteIssue issue=createJiraIssue(build,filename);
                        listener.getLogger().println( "Creating jira issue with issue ID"+
                                " "+issue.getKey());
                    }
                } catch (ServiceException e) {
                    e.printStackTrace();
                }
            }
        }

        if(previousBuildResult==Result.SUCCESS || previousBuildResult==Result.ABORTED) {
            try {
                RemoteIssue issue=createJiraIssue(build,filename);
                listener.getLogger().println("**************************Test Fails************" +
                        "******************");
                listener.getLogger().println( "Creating jira issue with issue ID"
                        +" "+issue.getKey());

            } catch(ServiceException e) {
                System.out.print("Service Exception");
                e.printStackTrace();
            }
        }
    }

    /**
     * when the current build's result is "success",
     * it checks for the previous build's result and adds comment until the previously created issue is closed.
     *
     * @param build
     * @param previousBuildResult
     * @param filename
     * @param environmentVariable
     * @throws InterruptedException
     * @throws IOException
     */
    public void currentBuildResultSuccess(AbstractBuild<?, ?> build,Result previousBuildResult,
                    String filename,EnvVars environmentVariable) throws InterruptedException,IOException {
        String buildURL=environmentVariable.get("BUILD_URL");
        String buildNumber=environmentVariable.get("BUILD_NUMBER");

        if (previousBuildResult==Result.FAILURE || previousBuildResult==Result.SUCCESS) {
            String comment="- Job is not falling but the issue is still open."+"\n"+"- Passed run : ["+
                    buildNumber+"|"+buildURL+"]"+"\n"+ "** [console log|"+buildURL.concat("console")+"]";
            String issueId=getIssue(filename);

            //if issue exists it will check the status and comment or delete the file accordingly
            if (issueId!=null) {
                try {
                    String Status=getStatus(build,issueId);

                    //Status=1=Open OR Status=5=Resolved
                    if (Status.equals("1") ||Status.equals("5")) {
                        addComment(build, issueId, comment);
                    }

                    //if issue is in closed status
                    if (Status.equals("6")) {
                        deleteFile(filename);
                    }
                } catch(ServiceException e) {
                    System.out.println("Service Exception");
                    e.printStackTrace();
                }
            }
        }
    }

    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(JiraCreateIssueNotifier.class);
        }

        public FormValidation doCheckProjectKey(@QueryParameter String value)
                throws IOException {
            if (value.length() == 0) {
                return FormValidation.error("Please set the project key");
            }
            return FormValidation.ok();
        }

        @Override
        public JiraCreateIssueNotifier newInstance(StaplerRequest req,
                                             JSONObject formData) throws FormException {
            return req.bindJSON(JiraCreateIssueNotifier.class, formData);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Create Jira Issue" ;
        }

        @Override
        public String getHelpFile() {
            return "/plugin/jira/help-jira-create-issue.html";
        }
    }
}
