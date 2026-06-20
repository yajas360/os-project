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
        Process process; // Reference to the actual process for life-cycle tracking

        public BackgroundJob(int jobId, long pid, String status, String command, Process process) {
            this.jobId = jobId;
            this.pid = pid;
            this.status = status;
            this.command = command;
            this.process = process;
        }
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
            } else if (Character.isWhitespace(c)
                    && !inSingleQuotes
                    && !inDoubleQuotes) {

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
        
        // List to hold active background jobs
        List<BackgroundJob> backgroundJobs = new ArrayList<>();
        
        // Persistent global counter to guarantee stable, sequential job IDs
        int jobCounter = 0;

        while (true) {
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
                
                // Remove the '&' token from the arguments execution array
                String[] newParts = new String[parts.length - 1];
                System.arraycopy(parts, 0, newParts, 0, parts.length - 1);
                parts = newParts;
            }

            if (parts.length == 0) {
                continue;
            }

            String outputFile = null;
            String errorFile = null;
            boolean appendOutput = false;
            boolean appendError = false;
            List<String> cleaned = new ArrayList<>();

            for (int i = 0; i < parts.length; i++) {
                if ((parts[i].equals(">") || parts[i].equals("1>"))
                        && i + 1 < parts.length) {
                    outputFile = parts[i + 1];
                    appendOutput = false;
                    i++;
                } else if ((parts[i].equals(">>") || parts[i].equals("1>>"))
                        && i + 1 < parts.length) {
                    outputFile = parts[i + 1];
                    appendOutput = true;
                    i++;
                } else if (parts[i].equals("2>")
                        && i + 1 < parts.length) {
                    errorFile = parts[i + 1];
                    appendError = false;
                    i++;
                } else if (parts[i].equals("2>>")
                        && i + 1 < parts.length) {
                    errorFile = parts[i + 1];
                    appendError = true;
                    i++;
                } else {
                    cleaned.add(parts[i]);
                }
            }

            parts = cleaned.toArray(new String[0]);

            if (parts.length == 0) {
                continue;
            }

            String cmd = parts[0];

            if (cmd.equals("exit")) {
                break;
            }

            if (cmd.equals("pwd")) {
                String output = currentDirectory.getCanonicalPath();

                if (outputFile != null) {
                    if (appendOutput) {
                        Files.write(
                                Paths.get(outputFile),
                                (output + System.lineSeparator()).getBytes(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND
                        );
                    } else {
                        Files.write(
                                Paths.get(outputFile),
                                (output + System.lineSeparator()).getBytes()
                        );
                    }
                } else {
                    System.out.println(output);
                }

                if (errorFile != null) {
                    if (appendError) {
                        Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.write(Paths.get(errorFile), new byte[0]);
                    }
                }

                continue;
            }

            if (cmd.equals("cd")) {
                if (parts.length < 2) {
                    continue;
                }

                String path = parts[1];
                File target;

                if (path.equals("~")) {
                    target = new File(System.getenv("HOME"));
                } else if (new File(path).isAbsolute()) {
                    target = new File(path);
                } else {
                    target = new File(currentDirectory, path);
                }

                if (target.exists() && target.isDirectory()) {
                    currentDirectory = target.getCanonicalFile();
                } else {
                    String err = "cd: " + path + ": No such file or directory";

                    if (errorFile != null) {
                        if (appendError) {
                            Files.write(
                                    Paths.get(errorFile),
                                    (err + System.lineSeparator()).getBytes(),
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
                            );
                        } else {
                            Files.write(
                                    Paths.get(errorFile),
                                    (err + System.lineSeparator()).getBytes()
                        );
                        }
                    } else {
                        System.out.println(err);
                    }
                }

                continue;
            }

            if (cmd.equals("echo")) {
                StringBuilder sb = new StringBuilder();

                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) {
                        sb.append(" ");
                    }
                    sb.append(parts[i]);
                }

                if (outputFile != null) {
                    if (appendOutput) {
                        Files.write(
                                Paths.get(outputFile),
                                (sb.toString() + System.lineSeparator()).getBytes(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND
                        );
                    } else {
                        Files.write(
                                Paths.get(outputFile),
                                (sb.toString() + System.lineSeparator()).getBytes()
                        );
                    }
                } else {
                    System.out.println(sb);
                }

                if (errorFile != null) {
                    if (appendError) {
                        Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.write(Paths.get(errorFile), new byte[0]);
                    }
                }

                continue;
            }

            if (cmd.equals("jobs")) {
                // 1. Scan and capture completed jobs asynchronously first
                for (BackgroundJob job : backgroundJobs) {
                    if (job.status.equals("Running") && !job.process.isAlive()) {
                        job.status = "Done";
                    }
                }

                StringBuilder jobsOutput = new StringBuilder();
                int numJobs = backgroundJobs.size();

                // 2. Generate visualization with contextual dynamic markers
                for (int i = 0; i < numJobs; i++) {
                    BackgroundJob job = backgroundJobs.get(i);
                    
                    char marker = ' ';
                    if (i == numJobs - 1) {
                        marker = '+';
                    } else if (i == numJobs - 2) {
                        marker = '-';
                    }

                    String displayCommand = job.command;
                    if (job.status.equals("Running")) {
                        displayCommand += " &";
                    }

                    String formattedStatus = String.format("%-24s", job.status);
                    jobsOutput.append(String.format("[%d]%c  %s%s%n", job.jobId, marker, formattedStatus, displayCommand));
                }
                String output = jobsOutput.toString();

                if (outputFile != null) {
                    if (appendOutput) {
                        Files.write(Paths.get(outputFile), output.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.write(Paths.get(outputFile), output.getBytes());
                    }
                } else {
                    System.out.print(output);
                }

                if (errorFile != null) {
                    if (appendError) {
                        Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.write(Paths.get(errorFile), new byte[0]);
                    }
                }

                // 3. Reap completed 'Done' items from tracking list *after* displaying them once
                backgroundJobs.removeIf(job -> job.status.equals("Done"));

                continue;
            }

            if (cmd.equals("type")) {
                if (parts.length < 2) {
                    continue;
                }

                String targetCmd = parts[1];
                String result;

                if (targetCmd.equals("echo") ||
                    targetCmd.equals("exit") ||
                    targetCmd.equals("type") ||
                    targetCmd.equals("pwd") ||
                    targetCmd.equals("cd") ||
                    targetCmd.equals("jobs")) {

                    result = targetCmd + " is a shell builtin";
                } else {
                    String pathEnv = System.getenv("PATH");
                    String[] paths = pathEnv.split(File.pathSeparator);

                    result = targetCmd + ": not found";

                    for (String path : paths) {
                        File file = new File(path, targetCmd);

                        if (file.exists() && file.isFile() && file.canExecute()) {
                            result = targetCmd + " is " + file.getAbsolutePath();
                            break;
                        }
                    }
                }

                if (outputFile != null) {
                    if (appendOutput) {
                        Files.write(
                                Paths.get(outputFile),
                                (result + System.lineSeparator()).getBytes(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND
                        );
                    } else {
                        Files.write(
                                Paths.get(outputFile),
                                (result + System.lineSeparator()).getBytes()
                        );
                    }
                } else {
                    System.out.println(result);
                }

                if (errorFile != null) {
                    if (appendError) {
                        Files.write(Paths.get(errorFile), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.write(Paths.get(errorFile), new byte[0]);
                    }
                }

                continue;
            }

            String pathEnv = System.getenv("PATH");
            String[] paths = pathEnv.split(File.pathSeparator);

            File executable = null;

            for (String path : paths) {
                File file = new File(path, cmd);

                if (file.exists() && file.isFile() && file.canExecute()) {
                    executable = file;
                    break;
                }
            }

            if (executable != null) {
                List<String> commandParts = new ArrayList<>();

                boolean quotedExecutable =
                        cmd.contains(" ") ||
                        cmd.contains("\"") ||
                        cmd.contains("'") ||
                        cmd.contains("\\");

                if (quotedExecutable) {
                    commandParts.add(executable.getAbsolutePath());
                } else {
                    commandParts.add(cmd);
                }

                for (int i = 1; i < parts.length; i++) {
                    commandParts.add(parts[i]);
                }

                ProcessBuilder pb = new ProcessBuilder(commandParts);
                pb.directory(currentDirectory);

                if (outputFile != null) {
                    if (appendOutput) {
                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                    } else {
                        pb.redirectOutput(new File(outputFile));
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                if (errorFile != null) {
                    if (appendError) {
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorFile)));
                    } else {
                        pb.redirectError(new File(errorFile));
                    }
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();
                
                if (runInBackground) {
                    jobCounter++;
                    int nextJobId = jobCounter;
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
                    if (appendError) {
                        Files.write(
                                Paths.get(errorFile),
                                (err + System.lineSeparator()).getBytes(),
                                StandardOpenOption.CREATE, StandardOpenOption.APPEND
                        );
                    } else {
                        Files.write(
                                Paths.get(errorFile),
                                (err + System.lineSeparator()).getBytes()
                        );
                    }
                } else {
                    System.out.println(err);
                }
            }
        }
    }
}