import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    // Helper class to track background job data
    static class BackgroundJob {
        int jobId;
        long pid;
        String status;
        String command;
        Process process;

        public BackgroundJob(int jobId, long pid, String status, String command, Process process) {
            this.jobId = jobId;
            this.pid = pid;
            this.status = status;
            this.command = command;
            this.process = process;
        }
    }

    // Helper class to hold parsed redirection details
    static class RedirectionResult {
        String[] cleanedArgs;
        String outputFile = null;
        String errorFile = null;
        boolean appendOutput = false;
        boolean appendError = false;
    }

    // Helper method to look up binaries in the system PATH
    private static File findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String path : paths) {
            File file = new File(path, cmd);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    // Extracts redirection tokens cleanly from a command segment
    private static RedirectionResult parseRedirections(String[] parts) {
        RedirectionResult res = new RedirectionResult();
        List<String> cleaned = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            if ((parts[i].equals(">") || parts[i].equals("1>")) && i + 1 < parts.length) {
                res.outputFile = parts[i + 1];
                res.appendOutput = false;
                i++;
            } else if ((parts[i].equals(">>") || parts[i].equals("1>>")) && i + 1 < parts.length) {
                res.outputFile = parts[i + 1];
                res.appendOutput = true;
                i++;
            } else if (parts[i].equals("2>") && i + 1 < parts.length) {
                res.errorFile = parts[i + 1];
                res.appendError = false;
                i++;
            } else if (parts[i].equals("2>>") && i + 1 < parts.length) {
                res.errorFile = parts[i + 1];
                res.appendError = true;
                i++;
            } else {
                cleaned.add(parts[i]);
            }
        }
        res.cleanedArgs = cleaned.toArray(new String[0]);
        return res;
    }

    private static String[] parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                        current.append(next);
                        i++;
                    }
                } else {
                    current.append('\\');
                }
            } else if (!inSingleQuotes && !inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));
        List<BackgroundJob> backgroundJobs = new ArrayList<>();

        while (true) {
            // --- Automatic Reaping Before Each Prompt ---
            for (BackgroundJob job : backgroundJobs) {
                if (job.status.equals("Running") && !job.process.isAlive()) {
                    job.status = "Done";
                }
            }

            int numJobsBeforePrompt = backgroundJobs.size();
            for (int i = 0; i < numJobsBeforePrompt; i++) {
                BackgroundJob job = backgroundJobs.get(i);
                if (job.status.equals("Done")) {
                    char marker = (i == numJobsBeforePrompt - 1) ? '+' : ((i == numJobsBeforePrompt - 2) ? '-' : ' ');
                    String formattedStatus = String.format("%-24s", job.status);
                    System.out.printf("[%d]%c  %s%s%n", job.jobId, marker, formattedStatus, job.command);
                }
            }
            backgroundJobs.removeIf(job -> job.status.equals("Done"));

            System.out.print("$ ");
            System.out.flush();

            String command = scanner.nextLine();
            String[] parts = parseCommand(command);

            if (parts.length == 0) {
                continue;
            }

            boolean runInBackground = false;
            if (parts[parts.length - 1].equals("&")) {
                runInBackground = true;
                String[] newParts = new String[parts.length - 1];
                System.arraycopy(parts, 0, newParts, 0, parts.length - 1);
                parts = newParts;
            }

            if (parts.length == 0) {
                continue;
            }

            // --- Pipeline Detection Block ---
            int pipeIndex = -1;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("|")) {
                    pipeIndex = i;
                    break;
                }
            }

            if (pipeIndex != -1) {
                // Split tokens across the pipe operator
                String[] parts1 = new String[pipeIndex];
                System.arraycopy(parts, 0, parts1, 0, pipeIndex);

                String[] parts2 = new String[parts.length - pipeIndex - 1];
                System.arraycopy(parts, pipeIndex + 1, parts2, 0, parts2.length);

                RedirectionResult red1 = parseRedirections(parts1);
                RedirectionResult red2 = parseRedirections(parts2);

                if (red1.cleanedArgs.length == 0 || red2.cleanedArgs.length == 0) {
                    continue;
                }

                String cmd1 = red1.cleanedArgs[0];
                String cmd2 = red2.cleanedArgs[0];

                File exec1 = findExecutable(cmd1);
                File exec2 = findExecutable(cmd2);

                if (exec1 == null) {
                    System.out.println(cmd1 + ": command not found");
                    continue;
                }
                if (exec2 == null) {
                    System.out.println(cmd2 + ": command not found");
                    continue;
                }

                // Prepare Builders
                List<String> commandParts1 = new ArrayList<>();
                commandParts1.add(cmd1.matches(".*[ '\"\\\\].*") ? exec1.getAbsolutePath() : cmd1);
                for (int i = 1; i < red1.cleanedArgs.length; i++) commandParts1.add(red1.cleanedArgs[i]);

                List<String> commandParts2 = new ArrayList<>();
                commandParts2.add(cmd2.matches(".*[ '\"\\\\].*") ? exec2.getAbsolutePath() : cmd2);
                for (int i = 1; i < red2.cleanedArgs.length; i++) commandParts2.add(red2.cleanedArgs[i]);

                ProcessBuilder pb1 = new ProcessBuilder(commandParts1).directory(currentDirectory);
                ProcessBuilder pb2 = new ProcessBuilder(commandParts2).directory(currentDirectory);

                // Configure Pipe Redirections
                pb1.redirectInput(ProcessBuilder.Redirect.INHERIT);
                if (red1.errorFile != null) {
                    pb1.redirectError(red1.appendError ? ProcessBuilder.Redirect.appendTo(new File(red1.errorFile)) : ProcessBuilder.Redirect.to(new File(red1.errorFile)));
                } else {
                    pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                if (red2.outputFile != null) {
                    pb2.redirectOutput(red2.appendOutput ? ProcessBuilder.Redirect.appendTo(new File(red2.outputFile)) : ProcessBuilder.Redirect.to(new File(red2.outputFile)));
                } else {
                    pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                if (red2.errorFile != null) {
                    pb2.redirectError(red2.appendError ? ProcessBuilder.Redirect.appendTo(new File(red2.errorFile)) : ProcessBuilder.Redirect.to(new File(red2.errorFile)));
                } else {
                    pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                // Execute native OS pipe
                List<Process> pipelineProcesses = ProcessBuilder.startPipeline(List.of(pb1, pb2));

                if (runInBackground) {
                    int nextJobId = backgroundJobs.isEmpty() ? 1 : backgroundJobs.stream().mapToInt(j -> j.jobId).max().getAsInt() + 1;
                    Process lastProcess = pipelineProcesses.get(pipelineProcesses.size() - 1);
                    System.out.printf("[%d] %d%n", nextJobId, lastProcess.pid());

                    String cleanedCommand = command.trim();
                    if (cleanedCommand.endsWith("&")) {
                        cleanedCommand = cleanedCommand.substring(0, cleanedCommand.length() - 1).trim();
                    }
                    backgroundJobs.add(new BackgroundJob(nextJobId, lastProcess.pid(), "Running", cleanedCommand, lastProcess));
                } else {
                    for (Process p : pipelineProcesses) {
                        p.waitFor();
                    }
                }
                continue;
            }

            // --- Single Command Path ---
            RedirectionResult red = parseRedirections(parts);
            parts = red.cleanedArgs;
            String outputFile = red.outputFile;
            String errorFile = red.errorFile;
            boolean appendOutput = red.appendOutput;
            boolean appendError = red.appendError;

            String cmd = parts[0];

            if (cmd.equals("exit")) {
                break;
            }

            if (cmd.equals("pwd")) {
                String output = currentDirectory.getCanonicalPath();
                if (outputFile != null) {
                    Files.write(Paths.get(outputFile), (output + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, appendOutput ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(output);
                }
                if (errorFile != null) {
                    Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                }
                continue;
            }

            if (cmd.equals("cd")) {
                if (parts.length < 2) continue;
                String path = parts[1];
                File target = path.equals("~") ? new File(System.getenv("HOME")) : (new File(path).isAbsolute() ? new File(path) : new File(currentDirectory, path));

                if (target.exists() && target.isDirectory()) {
                    currentDirectory = target.getCanonicalFile();
                } else {
                    String err = "cd: " + path + ": No such file or directory";
                    if (errorFile != null) {
                        Files.write(Paths.get(errorFile), (err + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                    } else {
                        System.out.println(err);
                    }
                }
                continue;
            }

            if (cmd.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(parts[i]);
                }
                if (outputFile != null) {
                    Files.write(Paths.get(outputFile), (sb.toString() + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, appendOutput ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(sb);
                }
                if (errorFile != null) {
                    Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                }
                continue;
            }

            if (cmd.equals("jobs")) {
                for (BackgroundJob job : backgroundJobs) {
                    if (job.status.equals("Running") && !job.process.isAlive()) {
                        job.status = "Done";
                    }
                }

                StringBuilder jobsOutput = new StringBuilder();
                int numJobs = backgroundJobs.size();

                for (int i = 0; i < numJobs; i++) {
                    BackgroundJob job = backgroundJobs.get(i);
                    char marker = (i == numJobs - 1) ? '+' : ((i == numJobs - 2) ? '-' : ' ');
                    String displayCommand = job.command + (job.status.equals("Running") ? " &" : "");
                    String formattedStatus = String.format("%-24s", job.status);
                    jobsOutput.append(String.format("[%d]%c  %s%s%n", job.jobId, marker, formattedStatus, displayCommand));
                }
                String output = jobsOutput.toString();

                if (outputFile != null) {
                    Files.write(Paths.get(outputFile), output.getBytes(), StandardOpenOption.CREATE, appendOutput ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.print(output);
                }
                if (errorFile != null) {
                    Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                }

                backgroundJobs.removeIf(job -> job.status.equals("Done"));
                continue;
            }

            if (cmd.equals("type")) {
                if (parts.length < 2) continue;
                String targetCmd = parts[1];
                String result = (targetCmd.equals("echo") || targetCmd.equals("exit") || targetCmd.equals("type") || targetCmd.equals("pwd") || targetCmd.equals("cd") || targetCmd.equals("jobs")) ?
                        targetCmd + " is a shell builtin" : (findExecutable(targetCmd) != null ? targetCmd + " is " + findExecutable(targetCmd).getAbsolutePath() : targetCmd + ": not found");

                if (outputFile != null) {
                    Files.write(Paths.get(outputFile), (result + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, appendOutput ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(result);
                }
                if (errorFile != null) {
                    Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                }
                continue;
            }

            File executable = findExecutable(cmd);

            if (executable != null) {
                List<String> commandParts = new ArrayList<>();
                commandParts.add(cmd.matches(".*[ '\"\\\\].*") ? executable.getAbsolutePath() : cmd);
                for (int i = 1; i < parts.length; i++) commandParts.add(parts[i]);

                ProcessBuilder pb = new ProcessBuilder(commandParts).directory(currentDirectory);

                if (outputFile != null) {
                    pb.redirectOutput(appendOutput ? ProcessBuilder.Redirect.appendTo(new File(outputFile)) : ProcessBuilder.Redirect.to(new File(outputFile)));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                if (errorFile != null) {
                    pb.redirectError(appendError ? ProcessBuilder.Redirect.appendTo(new File(errorFile)) : ProcessBuilder.Redirect.to(new File(errorFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();

                if (runInBackground) {
                    int nextJobId = backgroundJobs.isEmpty() ? 1 : backgroundJobs.stream().mapToInt(j -> j.jobId).max().getAsInt() + 1;
                    System.out.printf("[%d] %d%n", nextJobId, process.pid());

                    String cleanedCommand = command.trim();
                    if (cleanedCommand.endsWith("&")) {
                        cleanedCommand = cleanedCommand.substring(0, cleanedCommand.length() - 1).trim();
                    }
                    backgroundJobs.add(new BackgroundJob(nextJobId, process.pid(), "Running", cleanedCommand, process));
                } else {
                    process.waitFor();
                }
            } else {
                String err = command + ": command not found";
                if (errorFile != null) {
                    Files.write(Paths.get(errorFile), (err + System.lineSeparator()).getBytes(), StandardOpenOption.CREATE, appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(err);
                }
            }
        }
    }
}