package de.unipassau.rustyunit.exec;

import com.jayway.jsonpath.JsonPath;
import de.unipassau.rustyunit.Constants;
import de.unipassau.rustyunit.Main.CLI;
import de.unipassau.rustyunit.exception.TestCaseDoesNotCompileException;
import de.unipassau.rustyunit.server.RedisStorage;
import de.unipassau.rustyunit.source.ChromosomeContainer;
import de.unipassau.rustyunit.test_case.TestCase;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestCaseRunner implements ChromosomeExecutor<TestCase> {

  private static final Logger logger = LoggerFactory.getLogger(TestCaseRunner.class);

  private static final Path SCRIPTS_PATH = Paths.get(System.getProperty("user.dir"), "scripts");

  private final Path logPath;
  private final Path errorPath;
  private final Path coverageDir;
  private final Path instrumenter;
  private final String features;

  private int run;

  public TestCaseRunner(CLI cli, String executionRoot) {
    this.coverageDir = Paths.get(executionRoot, "coverage");
    this.logPath = Paths.get(cli.getOutputDir(), "tests.log");
    this.errorPath = Paths.get(cli.getOutputDir(), "tests.error");
    this.instrumenter = Paths.get(cli.getInstrumenterPath());
    this.features = cli.features();
    this.run = cli.getRun();
  }

  private void clear() {
    Arrays.stream(Objects.requireNonNull(coverageDir.toFile().listFiles())).filter(File::isFile)
        .forEach(File::delete);
  }

  private int collectCoverageFiles(File directory) throws IOException, InterruptedException {
    var processBuilder = new ProcessBuilder("cargo", "test",
        Constants.TEST_MOD_NAME, "--features", features).directory(directory).redirectOutput(logPath.toFile())
        .redirectError(errorPath.toFile());
    var env = processBuilder.environment();
    env.put("RUSTFLAGS", "-C instrument-coverage");

    var profRawFileName = String.format("%s-%%m.profraw", Constants.TEST_PREFIX.replace("_", "-"));
    env.put("LLVM_PROFILE_FILE", Paths.get(coverageDir.toString(), profRawFileName).toString());

    var process = processBuilder.start();
    return process.waitFor();
  }

  private int mergeCoverageFiles(File directory) throws IOException, InterruptedException {
    var profRawFiles = Paths.get(coverageDir.toFile().getCanonicalPath(), "rusty-test*.profraw");
    var command = String.format(
        "cargo %s profdata -- merge -sparse %s -o %s",
        profRawFiles,
        Paths.get(coverageDir.toFile().getCanonicalPath(), "rusty-tests.profdata"));

    var processBuilder = new ProcessBuilder("bash", "-c", command).directory(directory)
        .redirectOutput(logPath.toFile()).redirectError(errorPath.toFile());
    var process = processBuilder.start();
    return process.waitFor();
  }

  private Pair<Integer, String> createCoverageReport(File directory)
      throws InterruptedException, IOException {
    var script = Paths.get(SCRIPTS_PATH.toString(), "coverage-report.sh").toString();

    var profdata = Paths.get(coverageDir.toString(), "rusty-tests.profdata").toString();

    var processBuilder = new ProcessBuilder(script, profdata).directory(directory)
        .redirectError(errorPath.toFile());
    var process = processBuilder.start();

    var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    var sb = new StringBuilder();
    String line = null;
    while ((line = reader.readLine()) != null) {
      sb.append(line);
      sb.append(System.getProperty("line.separator"));
    }
    var output = sb.toString();

    return Pair.with(process.waitFor(), output);
  }

  @Override
  public LLVMCoverage run(ChromosomeContainer<TestCase> container)
      throws IOException, InterruptedException {
    return run(container.getPath());
  }

  public LLVMCoverage run(String path) throws IOException, InterruptedException {
    clear();

    var directory = new File(path);
    if (collectCoverageFiles(directory) != 0) {
      logger.error("Could not run tests for some reason");
      throw new RuntimeException("Could not run tests for some reason");
    }

    if (mergeCoverageFiles(directory) != 0) {
      logger.error("Could not merge tests for some reason");
      throw new RuntimeException("Could not merge tests for some reason");
    }

    var coverageResult = createCoverageReport(directory);
    if (coverageResult.getValue0() != 0) {
      logger.error("Could not create a coverage report");
      throw new RuntimeException("Could not create a coverage report");
    }

    var lineCoverageRaw = JsonPath.read(coverageResult.getValue1(), "$.data[0].totals.lines.percent");
    var regionCoverageRaw = JsonPath.read(coverageResult.getValue1(), "$.data[0].totals.regions.percent");

    double lineCoverage;
    if (lineCoverageRaw instanceof Double) {
      lineCoverage = (double) lineCoverageRaw;
    } else if (lineCoverageRaw instanceof BigDecimal) {
      lineCoverage = ((BigDecimal) lineCoverageRaw).doubleValue();
    } else {
      throw new RuntimeException("Not implemented");
    }

    double regionCoverage;
    if (regionCoverageRaw instanceof Double) {
      regionCoverage = (double) regionCoverageRaw;
    } else if (regionCoverageRaw instanceof BigDecimal) {
      regionCoverage = ((BigDecimal) regionCoverageRaw).doubleValue();
    } else {
      throw new RuntimeException("Not implemented");
    }

    return new LLVMCoverage(lineCoverage, regionCoverage);
  }

  private Optional<List<Integer>> executeTestsWithInstrumentation(File directory, String crateName)
      throws IOException, InterruptedException, TestCaseDoesNotCompileException {
    var timer = new Timer();
    timer.start();
    var processBuilder = new ProcessBuilder("cargo", "instrumentation", "--features", features)
        .directory(directory)
        .redirectError(errorPath.toFile());

    var env = processBuilder.environment();
    // env.put("RUSTC_WRAPPER", instrumenter.toString());
    env.put("RUST_LOG", "info");
    env.put("RU_STAGE", "instrumentation");
    env.put("RU_CRATE_NAME", crateName);
    env.put("RU_CRATE_ROOT", directory.toString());
    env.put("RU_RUN", String.valueOf(run));
    var process = processBuilder.start();
    var cargo_output = IOUtils.toString(process.getErrorStream(), Charset.defaultCharset());
    process.waitFor();

    var cargologpath = Paths.get(directory.getAbsolutePath(), "build", "cargo_" + System.currentTimeMillis() + ".log");
    cargologpath.getParent().toFile().mkdirs();
    try (var writer = Files.newBufferedWriter(cargologpath)) {
      writer.write(cargo_output);
      writer.flush();
    }

    var processBuilder2 = new ProcessBuilder("cargo", "test",
        Constants.TEST_MOD_NAME, "--features", features)
        .directory(directory)
        .redirectError(errorPath.toFile());

    var env2 = processBuilder2.environment();
    // env.put("RUSTC_WRAPPER", instrumenter.toString());
    env2.put("RUST_LOG", "info");
    env2.put("RU_STAGE", "instrumentation");
    env2.put("RU_CRATE_NAME", crateName);
    env2.put("RU_CRATE_ROOT", directory.toString());
    env2.put("RU_RUN", String.valueOf(run));
    var process2 = processBuilder2.start();
    var output = IOUtils.toString(process2.getInputStream(), Charset.defaultCharset());
    var statusCode = process2.waitFor();

    var path = Paths.get(directory.getAbsolutePath(), "build", System.currentTimeMillis() + ".log");
    path.getParent().toFile().mkdirs();
    try (var writer = Files.newBufferedWriter(path)) {
      writer.write(output);
      writer.flush();
    }

    var elapsedTime = timer.end();
    if (statusCode != 0) {
      if (output.contains("test result: FAILED.")) {
        // Some tests failed

        List<Integer> failedTests = new ArrayList<>();
        for (String line : output.split("\n")) {
          if (line.startsWith("test") && line.endsWith("FAILED")) {
            var data = line.substring(line.lastIndexOf("_") + 1, line.indexOf(" ..."));
            var testId = Integer.parseInt(data);
            failedTests.add(testId);
          }
        }
        return Optional.of(failedTests);
      } else {
        // Tests did not compile
        throw new TestCaseDoesNotCompileException();
      }
    } else {
      return Optional.empty();
    }
  }

  @Override
  public Status runWithInstrumentation(ChromosomeContainer<TestCase> container)
      throws IOException, InterruptedException {
    RedisStorage.clear();

    var directory = new File(container.getPath());
    Optional<List<Integer>> failedTestIds;
    try {
      failedTestIds = executeTestsWithInstrumentation(directory, container.getName());
    } catch (TestCaseDoesNotCompileException e) {
      logger.error("Tests did not compile", e);
      return Status.COMPILATION_ERROR;
    }

    failedTestIds.ifPresent(tests -> logger.info(tests.size() + " tests failed"));
    var coverage = RedisStorage.<TestCase>requestTraces();

    if (failedTestIds.isPresent()) {
      var ids = failedTestIds.get();
      var failedTests = container.chromosomes().stream().filter(t -> ids.contains(t.getId())).toList();
      container.chromosomes().removeAll(failedTests);
      failedTests.forEach(t -> t.metadata().setFails(true));
    }

    for (TestCase testCase : container.chromosomes()) {
      var testCoverage = coverage.get(testCase.getId());
      testCase.setCoverage(testCoverage);
    }

    container.refresh();
    return Status.OK;
  }
}
