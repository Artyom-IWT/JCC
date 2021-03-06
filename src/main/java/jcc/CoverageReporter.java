package jcc;

import org.jacoco.core.analysis.*;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.runner.JUnitCore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.util.*;

import jcc.Snatch.CompiledClassLoader;


public class CoverageReporter {

    private final CompiledClassLoader compiledClassLoader;

    private final MemoryClassLoader instrAndTestsClassLoader;

    private final List<String> tests;

    public CoverageReporter(URLClassLoader baseClassLoader, String testsPath) throws IOException {

        Snatch snatch = new Snatch(baseClassLoader);
        snatch.generateAll(testsPath);

        this.compiledClassLoader = snatch.getCompiledClassLoader();
        this.instrAndTestsClassLoader = new MemoryClassLoader();
        this.tests = snatch.getTestsNames();
    }

    public void execute() throws Exception {

        final IRuntime runtime = new LoggerRuntime();

        InputStream original;

        List<String> classes = new ArrayList<>();

        String fullyQualifiedName = "jcc.Adder";
        System.out.println("jcc/Adder.class" + " - " + fullyQualifiedName);
        original = compiledClassLoader.getResourceAsStream("jcc/Adder.class");
        Objects.requireNonNull(original);
        final Instrumenter instr = new Instrumenter(runtime);
        final byte[] instrumented = instr.instrument(original, fullyQualifiedName);
        original.close();
        instrAndTestsClassLoader.addDefinition(fullyQualifiedName, instrumented);
        classes.add("jcc/Adder.class");

        final RuntimeData data = new RuntimeData();
        runtime.startup(data);

        System.out.println("\nRunning tests...\n");

        for (String testName : tests) {
            instrAndTestsClassLoader.addDefinition(testName, compiledClassLoader.getBytes(testName));
            final Class<?> testClass = instrAndTestsClassLoader.loadClass(testName);
            JUnitCore.runClasses(testClass);
        }

        System.out.println("\nAnalyzing Coverage...\n");

        final ExecutionDataStore executionData = new ExecutionDataStore();
        final SessionInfoStore sessionInfos = new SessionInfoStore();
        data.collect(executionData, sessionInfos, false);
        runtime.shutdown();

        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);

        for (String className : classes) {
            original = compiledClassLoader.getResourceAsStream(className);
            Objects.requireNonNull(original);
            analyzer.analyzeClass(original, getFullyQualifiedName(className));
            original.close();
        }

        for (final IClassCoverage cc : coverageBuilder.getClasses()) {
            String className = cc.getName();
            System.out.printf("Coverage of class %s:%n", className);

            printCounter("instructions", cc.getInstructionCounter());
            printCounter("branches", cc.getBranchCounter());
            printCounter("lines", cc.getLineCounter());
            printCounter("methods", cc.getMethodCounter());
            printCounter("complexity", cc.getComplexityCounter());

            System.out.printf("%nCoverage of %s methods:%n", className);

            for (final IMethodCoverage mc : cc.getMethods()) {
                System.out.printf("%nCoverage of method %s:%n", mc.getName());

                printCounter("instructions", mc.getInstructionCounter());
                printCounter("branches", mc.getBranchCounter());
                printCounter("lines", mc.getLineCounter());
                printCounter("methods", mc.getMethodCounter());
                printCounter("complexity", mc.getComplexityCounter());
            }
        }

    }

    private String getFullyQualifiedName(String name) {
        return name.substring(0, name.length() - 6).replace('/', '.');
    }

    private void printCounter(final String unit, final ICounter counter) {
        final Integer covered = counter.getCoveredCount();
        final Integer total = counter.getTotalCount();
        System.out.printf("%s of %s %s covered%n", covered, total, unit);
    }

    public class MemoryClassLoader extends ClassLoader {

        private final Map<String, byte[]> definitions = new HashMap<>();

        public void addDefinition(final String name, final byte[] bytes) {
            definitions.put(name, bytes);
        }

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = definitions.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return compiledClassLoader.loadClass(name);
        }
    }

}
