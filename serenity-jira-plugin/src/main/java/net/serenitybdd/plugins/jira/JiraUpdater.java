package net.serenitybdd.plugins.jira;


import ch.lambdaj.function.convert.Converter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import net.serenitybdd.plugins.jira.domain.IssueComment;
import net.serenitybdd.plugins.jira.guice.Injectors;
import net.serenitybdd.plugins.jira.model.IssueTracker;
import net.serenitybdd.plugins.jira.model.NamedTestResult;
import net.serenitybdd.plugins.jira.model.TestResultComment;
import net.serenitybdd.plugins.jira.service.JIRAConfiguration;
import net.serenitybdd.plugins.jira.service.NoSuchIssueException;
import net.serenitybdd.plugins.jira.workflow.ClasspathWorkflowLoader;
import net.serenitybdd.plugins.jira.workflow.Workflow;
import net.serenitybdd.plugins.jira.workflow.WorkflowLoader;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.model.TestOutcomeSummary;
import net.thucydides.core.model.TestResult;
import net.thucydides.core.util.EnvironmentVariables;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ch.lambdaj.Lambda.convert;

/**
 * Class used for JIra interaction, to update comments in Jira issues.
 */
public class JiraUpdater {

    private static final String BUILD_ID_PROPERTY = "build.id";
    public static final String SKIP_JIRA_UPDATES = "serenity.skip.jira.updates";

    static int DEFAULT_MAX_THREADS = 4;
    private final IssueTracker issueTracker;

    private final ListeningExecutorService executorService;
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private EnvironmentVariables environmentVariables;
    private static final Logger LOGGER = LoggerFactory.getLogger(JiraUpdater.class);
    private final String projectPrefix;
    private Workflow workflow;
    private WorkflowLoader loader;
    private final JIRAConfiguration configuration;

    public JiraUpdater(IssueTracker issueTracker,
                       EnvironmentVariables environmentVariables,
                       WorkflowLoader loader)
    {
        this.issueTracker = issueTracker;
        this.environmentVariables = environmentVariables;
        this.loader = loader;
        configuration = Injectors.getInjector().getInstance(JIRAConfiguration.class);
        workflow = loader.load();
        this.projectPrefix = environmentVariables.getProperty(ThucydidesSystemProperty.JIRA_PROJECT.getPropertyName());
        executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(getMaxJobs()));
        logStatus(environmentVariables);
    }

    private void logStatus(EnvironmentVariables environmentVariables) {
        String jiraUrl = environmentVariables.getProperty(ThucydidesSystemProperty.JIRA_URL.getPropertyName());
        String reportUrl = ThucydidesSystemProperty.THUCYDIDES_PUBLIC_URL.from(environmentVariables,"");
        LOGGER.debug("JIRA LISTENER STATUS");
        LOGGER.debug("JIRA URL: {} ", jiraUrl);
        LOGGER.debug("REPORT URL: {} ", reportUrl);
        LOGGER.debug("WORKFLOW ACTIVE: {} ", workflow.isActive());
    }

    public void updateIssueStatus(Set<String> issues, final TestResultTally<TestOutcomeSummary> resultTally) {
        queueSize.set(issues.size());
        for(final String issue : issues) {
            final ListenableFuture<String> future = executorService.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return issue;
                }
            });
            future.addListener(new Runnable() {
                @Override
                public void run() {
                    logIssueTracking(issue);
                    if (!dryRun()) {
                        try {
                            updateIssue(issue, resultTally.getTestOutcomesForIssue(issue));
                        } catch (Throwable e) {
                            LOGGER.warn(issue +" could not be updated", e);
                            e.printStackTrace();
                        }
                        queueSize.decrementAndGet();
                    }
                }
            }, MoreExecutors.newDirectExecutorService());
//HUH??
//            future.addListener(new Runnable() {
//                @Override
//                public void run() {
//                    queueSize.decrementAndGet();
//                }
//            }, executorService);

        }
        waitTillUpdatesDone(queueSize);
    }

    private void waitTillUpdatesDone(AtomicInteger counter) {
        while (counter.get() > 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }
    }

    public boolean shouldUpdateIssues() {

        String jiraUrl = environmentVariables.getProperty(ThucydidesSystemProperty.JIRA_URL.getPropertyName());
        String reportUrl = ThucydidesSystemProperty.THUCYDIDES_PUBLIC_URL.from(environmentVariables,"");
        if (workflow.isActive()) {
            LOGGER.debug("WORKFLOW TRANSITIONS: {}", workflow.getTransitions());
        }

        return !(StringUtils.isEmpty(jiraUrl) || StringUtils.isEmpty(reportUrl));
    }

    private void updateIssue(String issueId, List<TestOutcomeSummary> testOutcomes) {

        try {
            TestResultComment testResultComment = newOrUpdatedCommentFor(issueId, testOutcomes);
            if (getWorkflow().isActive() && shouldUpdateWorkflow()) {
                updateIssueStatusFor(issueId, testResultComment.getOverallResult());
            }
        } catch (NoSuchIssueException e) {
            LOGGER.error("No JIRA issue found with ID {}", issueId);
        }
    }

    private void updateIssueStatusFor(final String issueId, final TestResult testResult) {
        LOGGER.info("Updating status for issue {} with test result {}", issueId, testResult);
        String currentStatus = issueTracker.getStatusFor(issueId);

        LOGGER.info("Issue {} currently has status '{}'", issueId, currentStatus);

        List<String> transitions = getWorkflow().getTransitions().forTestResult(testResult).whenIssueIs(currentStatus);
        LOGGER.info("Found transitions {} for issue {}", transitions, issueId);

        for(String transition : transitions) {
            try {
                issueTracker.doTransition(issueId, transition);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private List<NamedTestResult> namedTestResultsFrom(List<TestOutcomeSummary> testOutcomes) {
        return convert(testOutcomes, toNamedTestResults());
    }

    private Converter<TestOutcomeSummary, NamedTestResult> toNamedTestResults() {
        return new Converter<TestOutcomeSummary, NamedTestResult>() {

            public NamedTestResult convert(TestOutcomeSummary from) {
                return new NamedTestResult(from.getTitle(), from.getTestResult());
            }
        };
    }

    private TestResultComment newOrUpdatedCommentFor(final String issueId, List<TestOutcomeSummary> testOutcomes) {
        LOGGER.info("Updating comments for issue {}", issueId);
        LOGGER.info("WIKI Rendering activated: {}", isWikiRenderedActive());

        List<IssueComment> comments = issueTracker.getCommentsFor(issueId);
        IssueComment existingComment = findExistingSerenityCommentIn(comments);
        String testRunNumber = environmentVariables.getProperty(BUILD_ID_PROPERTY);
        TestResultComment testResultComment;
        List<NamedTestResult> newTestResults = namedTestResultsFrom(testOutcomes);
        if (existingComment == null) {
            testResultComment = TestResultComment.comment(isWikiRenderedActive())
                    .withResults(namedTestResultsFrom(testOutcomes))
                    .withReportUrl(linkToReport(testOutcomes))
                    .withTestRun(testRunNumber).asComment();

            issueTracker.addComment(issueId, testResultComment.asText());
        } else {
            testResultComment = TestResultComment.fromText(existingComment.getBody())
                    .withWikiRendering(isWikiRenderedActive())
                    .withUpdatedTestResults(newTestResults)
                    .withUpdatedReportUrl(linkToReport(testOutcomes))
                    .withUpdatedTestRunNumber(testRunNumber);

            IssueComment updatedComment = existingComment.withText(testResultComment.asText());
            issueTracker.updateComment(issueId,updatedComment);
        }
        return testResultComment;
    }

    private IssueComment findExistingSerenityCommentIn(List<IssueComment> comments) {
        for (IssueComment comment : comments) {
            if (comment.getBody().contains("Thucydides Test Results")) {
                return comment;
            }
        }
        return null;
    }

    private void logIssueTracking(final String issueId) {
        if (dryRun()) {
            LOGGER.info("--- DRY RUN ONLY: JIRA WILL NOT BE UPDATED ---");
        }
        LOGGER.info("Updating JIRA issue: " + issueId);
        LOGGER.info("JIRA server: " + issueTracker.toString());
    }

    private boolean dryRun() {
        return Boolean.valueOf(environmentVariables.getProperty(SKIP_JIRA_UPDATES));
    }

    private String linkToReport(List<TestOutcomeSummary> testOutcomes) {
        TestOutcomeSummary firstTestOutcome = testOutcomes.get(0);
        String reportUrl = ThucydidesSystemProperty.THUCYDIDES_PUBLIC_URL.from(environmentVariables,"");
        String reportName = firstTestOutcome.getReportName() + ".html";
        return formatTestResultsLink(reportUrl, reportName);
    }

    private String formatTestResultsLink(String reportUrl, String reportName) {
        return reportUrl + "/" + reportName;
    }

    private boolean isWikiRenderedActive() {
        return configuration.isWikiRenderedActive();
    }

    public List<String> getPrefixedIssuesWithoutHashes(TestOutcomeSummary result)
    {
        return addPrefixesIfRequired(stripInitialHashesFrom(issueReferencesIn(result)));
    }

    private List<String> addPrefixesIfRequired(final List<String> issueNumbers) {
        return convert(issueNumbers, toIssueNumbersWithPrefixes());
    }

    private List<String> issueReferencesIn(TestOutcomeSummary result) {
        return result.getIssues();
    }

    private Converter<String, String> toIssueNumbersWithPrefixes() {
        return new Converter<String, String>() {
            public String convert(String issueNumber) {
                if (StringUtils.isEmpty(projectPrefix)) {
                    return issueNumber;
                }
                if (issueNumber.startsWith(projectPrefix)) {
                    return issueNumber;
                }
                return projectPrefix + "-" + issueNumber;
            }
        };
    }

    private List<String> stripInitialHashesFrom(final List<String> issueNumbers) {
        return convert(issueNumbers, toIssueNumbersWithoutHashes());
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    private Converter<String, String> toIssueNumbersWithoutHashes() {
        return new Converter<String, String>() {
            public String convert(String issueNumber) {

                if (issueNumber.startsWith("#")) {
                    return issueNumber.substring(1);
                } else {
                    return issueNumber;
                }

            }
        };
    }

    private int getMaxJobs() {
        return environmentVariables.getPropertyAsInteger("jira.max.threads",DEFAULT_MAX_THREADS);
    }

    protected Workflow getWorkflow() {
        return workflow;
    }

    protected boolean shouldUpdateWorkflow() {
        return Boolean.valueOf(environmentVariables.getProperty(ClasspathWorkflowLoader.ACTIVATE_WORKFLOW_PROPERTY));
    }

    public IssueTracker getIssueTracker() {
        return issueTracker;
    }

}
