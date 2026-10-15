package dev.openapifn.generator;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/** Runs compiled (generated) test classes with the JUnit Platform. */
final class Launch {

    record Summary(long succeeded, List<TestExecutionSummary.Failure> failures, String describe) {
    }

    private Launch() {
    }

    static Summary tests(Compilation compilation, String... classNames) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(compilation.classLoader);
        try {
            var selectors = Arrays.stream(classNames).map(compilation::loadClass).map(type -> selectClass(type)).toList();
            var request = LauncherDiscoveryRequestBuilder.request().selectors(selectors).build();
            var listener = new SummaryGeneratingListener();
            LauncherFactory.create().execute(request, listener);
            var summary = listener.getSummary();
            var text = new StringWriter();
            summary.printFailuresTo(new PrintWriter(text), 30);
            summary.printTo(new PrintWriter(text));
            return new Summary(summary.getTestsSucceededCount(), summary.getFailures(), text.toString());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
