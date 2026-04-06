/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com). All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ballerina.update.cmd;

import io.ballerina.cli.BLauncherCmd;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * WSO2 update command loaded via SPI.
 */
@CommandLine.Command(name = "update", description = "Run the WSO2 update tool.")
public class UpdateCmd implements BLauncherCmd {
    private static final String CMD_NAME = "update";
    private static final String UPDATE_TOOL_PREFIX = "wso2update_";
    private static final String ZIP_EXTENSION = ".zip";
    private static final String VERSION_PART_SEPARATOR_REGEX = "\\.";

    private final PrintStream outStream;
    private final PrintStream errStream;
    private final boolean exitWhenFinish;

    @CommandLine.Option(names = {"--help", "-h", "?"}, usageHelp = true)
    private boolean helpFlag;

    @CommandLine.Parameters(arity = "0..*")
    private List<String> argList = new ArrayList<>();

    @CommandLine.Unmatched
    private List<String> unmatchedArgs = new ArrayList<>();

    public UpdateCmd() {
        this.outStream = System.out;
        this.errStream = System.err;
        this.exitWhenFinish = true;
    }

    @Override
    public void execute() {
        if (helpFlag) {
            StringBuilder out = new StringBuilder();
            printLongDesc(out);
            outStream.println(out);
            return;
        }

        Path ballerinaHome = getBallerinaHome();
        try {
            extractRepoArchives(ballerinaHome.resolve("repo"));
            Path binDir = ballerinaHome.resolve("bin");
            Path updateTool = resolveUpdateToolBinary(binDir);
            if (updateTool == null) {
                runSetupScript(binDir);
                updateTool = resolveUpdateToolBinary(binDir);
                if (updateTool == null) {
                    errStream.println("ballerina: WSO2 update tool is not available in '" + binDir + "'.");
                    errStream.println("Run '" + binDir.resolve(getSetupScriptName()) + "' first.");
                    exitError();
                    return;
                }
            }

            List<String> command = new ArrayList<>();
            command.add(updateTool.toString());
            command.addAll(argList);
            command.addAll(unmatchedArgs);
            int exitCode = new ProcessBuilder(command)
                    .directory(binDir.toFile())
                    .inheritIO()
                    .start()
                    .waitFor();
            if (exitWhenFinish) {
                Runtime.getRuntime().exit(exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errStream.println("ballerina: failed to execute update command: " + e.getMessage());
            exitError();
        } catch (IOException e) {
            errStream.println("ballerina: failed to execute update command: " + e.getMessage());
            exitError();
        }
    }

    @Override
    public String getName() {
        return CMD_NAME;
    }

    @Override
    public void printLongDesc(StringBuilder out) {
        out.append(BLauncherCmd.getCommandUsageInfo(CMD_NAME, UpdateCmd.class.getClassLoader()));
    }

    @Override
    public void printUsage(StringBuilder out) {
        out.append("bal update [args]");
    }

    @Override
    public void setParentCmdParser(CommandLine parentCmdParser) {
    }

    private void extractRepoArchives(Path repoDir) throws IOException {
        if (Files.notExists(repoDir)) {
            return;
        }
        List<Path> archives;
        try (Stream<Path> files = Files.walk(repoDir)) {
            archives = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(ZIP_EXTENSION))
                    .sorted()
                    .toList();
        }

        for (Path archive : archives) {
            Path parentDir = archive.getParent();
            String targetDirectoryName = getTargetDirectoryName(archive.getFileName().toString());
            Path extractedDir = parentDir.resolve(targetDirectoryName);
            deletePath(extractedDir);
            Files.createDirectories(extractedDir);
            unzipArchive(archive, extractedDir, targetDirectoryName);
            Files.delete(archive);
        }
    }

    private void unzipArchive(Path archive, Path destinationDir, String targetDirectoryName) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (shouldSkipEntry(entryName)) {
                    zipInputStream.closeEntry();
                    continue;
                }

                String normalizedEntryName = normalizeEntryName(entryName, targetDirectoryName);
                if (normalizedEntryName.isBlank()) {
                    zipInputStream.closeEntry();
                    continue;
                }

                Path destination = destinationDir.resolve(normalizedEntryName).normalize();
                if (!destination.startsWith(destinationDir)) {
                    throw new IOException("invalid zip entry found in " + archive);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    if (destination.getParent() != null) {
                        Files.createDirectories(destination.getParent());
                    }
                    Files.write(destination, zipInputStream.readAllBytes(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private Path resolveUpdateToolBinary(Path binDir) throws IOException {
        if (Files.notExists(binDir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(binDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(this::isUpdateToolBinary)
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);
        }
    }

    private boolean isUpdateToolBinary(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
        return fileName.startsWith(UPDATE_TOOL_PREFIX) && !fileName.endsWith(".sh") && !fileName.endsWith(".ps1");
    }

    private static void deletePath(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private static Path getBallerinaHome() {
        String ballerinaHome = System.getProperty("ballerina.home");
        if (ballerinaHome == null || ballerinaHome.isBlank()) {
            throw new IllegalStateException("ballerina home is not available");
        }
        return Path.of(ballerinaHome);
    }

    private static String stripZipExtension(String fileName) {
        return fileName.substring(0, fileName.length() - ZIP_EXTENSION.length());
    }

    private static String getTargetDirectoryName(String fileName) {
        String baseName = stripZipExtension(fileName);
        String[] versionParts = baseName.split(VERSION_PART_SEPARATOR_REGEX);
        if (versionParts.length == 4 && isNumericVersion(versionParts)) {
            return String.join(".", versionParts[0], versionParts[1], versionParts[2]);
        }
        return baseName;
    }

    private static boolean isNumericVersion(String[] versionParts) {
        for (String versionPart : versionParts) {
            if (!versionPart.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldSkipEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return true;
        }
        String normalized = entryName.replace('\\', '/');
        return normalized.startsWith("__MACOSX/")
                || normalized.equals("__MACOSX")
                || normalized.contains("/._")
                || normalized.startsWith("._");
    }

    private static String normalizeEntryName(String entryName, String targetDirectoryName) {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.equals(targetDirectoryName) || normalized.equals(targetDirectoryName + "/")) {
            return "";
        }
        if (normalized.startsWith(targetDirectoryName + "/")) {
            return normalized.substring(targetDirectoryName.length() + 1);
        }
        int firstSeparatorIndex = normalized.indexOf('/');
        if (firstSeparatorIndex >= 0) {
            if (firstSeparatorIndex == normalized.length() - 1) {
                return "";
            }
            return normalized.substring(firstSeparatorIndex + 1);
        }
        return normalized;
    }

    private static String getSetupScriptName() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        return osName.contains("win") ? "update_tool_setup.ps1" : "update_tool_setup.sh";
    }

    private void runSetupScript(Path binDir) throws IOException, InterruptedException {
        Path setupScript = binDir.resolve(getSetupScriptName());
        if (Files.notExists(setupScript)) {
            return;
        }

        List<String> command = new ArrayList<>();
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (osName.contains("win")) {
            command.add("powershell");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(setupScript.toString());
        } else {
            command.add("sh");
            command.add(setupScript.toString());
        }

        int exitCode = new ProcessBuilder(command)
                .directory(binDir.toFile())
                .inheritIO()
                .start()
                .waitFor();
        if (exitCode != 0) {
            throw new IOException("failed to execute " + setupScript);
        }
    }

    private void exitError() {
        if (exitWhenFinish) {
            Runtime.getRuntime().exit(1);
        }
    }
}
