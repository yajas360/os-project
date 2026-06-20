import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

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

    static class RedirectionResult {
        String[] cleanedArgs;
        String outputFile = null;
        String errorFile = null;
        boolean appendOutput = false;
        boolean appendError = false;
    }

    private static File findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String path : paths) {
            File file = new File(path, cmd);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    private static boolean isBuiltIn(String cmd) {
        return cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || 
               cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("jobs");
    }

    private static RedirectionResult parseRedirections(String[] parts) {
        RedirectionResult res = new RedirectionResult();
        List<String> cleaned = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if ((parts[i].equals(">") || parts[i].equals("1>")) && i + 1 < parts.length) {
                res.outputFile = parts[i + 1]; res.appendOutput = false; i++;
            } else if ((parts[i].equals(">>") || parts[i].equals("1>>")) && i + 1 < parts.length) {
                res.outputFile = parts[i + 1]; res.appendOutput = true; i++;
            } else if (parts[i].equals("2>") && i + 1 < parts.length) {
                res.errorFile = parts[i + 1]; res.appendError = false; i++;
            } else if (parts[i].equals("2>>") && i + 1 < parts.length) {
                res.errorFile = parts[i + 1]; res.appendError = true; i++;
            } else {
                cleaned.add(parts[i]);
            }
        }
        res.cleanedArgs = cleaned.toArray(new String[0]);
        return res;
    }

    private static void touchRedirectionFiles(RedirectionResult red) throws IOException {
        if (red.outputFile != null) {
            File f = new File(red.outputFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            if (!red.appendOutput || !f.exists()) {
                try (FileOutputStream fos = new FileOutputStream(f, red.appendOutput)) {}
            }
        }
        if (red.errorFile != null) {
            File f = new File(red.errorFile);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            if (!red.appendError || !f.exists()) {
                try (FileOutputStream fos = new FileOutputStream(f, red.appendError)) {}
            }
        }
    }

    private static void flushTransfer(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
        }
    }

    private static void executeBuiltIn(String[] parts, InputStream in, PrintStream out, File currentDirectory, List<BackgroundJob> backgroundJobs) throws Exception {
        String cmd = parts[0];
        if (cmd.equals("pwd")) {
            out.println(currentDirectory.getCanonicalPath());
        } else if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) sb.append(" ");
                sb.append(parts[i]);
            }
            out.println(sb.toString());
        } else if (cmd.equals("type")) {
            if (parts.length < 2) return;
            String targetCmd = parts[1];
            if (isBuiltIn(targetCmd)) {
                out.println(targetCmd + " is a shell builtin");
            } else {
                File exec = findExecutable(targetCmd);
                out.println(exec != null ? targetCmd + " is " + exec.getAbsolutePath() : targetCmd + ": not found");
            }
        } else if (cmd.equals("jobs")) {
            for (BackgroundJob job : backgroundJobs) {
                if (job.status.equals("Running") && !job.process.isAlive()) {
                    job.status = "Done";
                }
            }
            int numJobs = backgroundJobs.size();
            for (int i = 0; i < numJobs; i++) {
                BackgroundJob job = backgroundJobs.get(i);
                char marker = (i == numJobs - 1) ? '+' : ((i == numJobs - 2) ? '-' : ' ');
                String displayCommand = job.command + (job.status.equals("Running") ? " &" : "");
                out.printf("[%d]%c  %-24s%s%n", job.jobId, marker, job.status, displayCommand);
            }
        }
    }

    private static String[] parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') { current.append(next); i++; }
                    else { current.append('\\'); current.append(next); i++; }
                } else { current.append('\\'); }
            } else if (!inSingleQuotes && !inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) { current.append(input.charAt(i + 1)); i++; }
            } else if (c == '\'' && !inDoubleQuotes) { inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) { inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) { args.add(current.toString()); current.setLength(0); }
            } else { current.append(c); }
        }
        if (current.length() > 0) args.add(current.toString());
        return args.toArray(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));
        List<BackgroundJob> backgroundJobs = new ArrayList<>();

        while (true) {
            // Give OS descriptive structure structures a brief sync down to Java
            Thread.sleep(40);

            // --- Automatic Reaping Before Prompt ---
            for (BackgroundJob job : backgroundJobs) {
                if (job.status.equals("Running") && !job.process.isAlive()) job.status = "Done";
            }
            int numJobsBeforePrompt = backgroundJobs.size();
            for (int i = 0; i < numJobsBeforePrompt; i++) {
                BackgroundJob job = backgroundJobs.get(i);
                if (job.status.equals("Done")) {
                    char marker = (i == numJobsBeforePrompt - 1) ? '+' : ((i == numJobsBeforePrompt - 2) ? '-' : ' ');
                    System.out.printf("[%d]%c  %-24s%s%n", job.jobId, marker, job.status, job.command);
                }
            }
            backgroundJobs.removeIf(job -> job.status.equals("Done"));

            System.out.print("$ ");
            System.out.flush();

            String command = scanner.nextLine();
            String[] parts = parseCommand(command);
            if (parts.length == 0) continue;

            boolean runInBackground = false;
            if (parts[parts.length - 1].equals("&")) {
                runInBackground = true;
                String[] newParts = new String[parts.length - 1];
                System.arraycopy(parts, 0, newParts, 0, parts.length - 1);
                parts = newParts;
            }
            if (parts.length == 0) continue;

            // --- Multi-Stage Pipeline Detection & Setup ---
            List<List<String>> pipelineStages = new ArrayList<>();
            List<String> currentStage = new ArrayList<>();
            
            for (String part : parts) {
                if (part.equals("|")) {
                    if (!currentStage.isEmpty()) {
                        pipelineStages.add(new ArrayList<>(currentStage));
                        currentStage.clear();
                    }
                } else {
                    currentStage.add(part);
                }
            }
            if (!currentStage.isEmpty()) {
                pipelineStages.add(currentStage);
            }

            if (pipelineStages.size() > 1) {
                int numStages = pipelineStages.size();
                PipedOutputStream[] pipelinesOut = new PipedOutputStream[numStages - 1];
                PipedInputStream[] pipelinesIn = new PipedInputStream[numStages - 1];
                
                for (int i = 0; i < numStages - 1; i++) {
                    pipelinesOut[i] = new PipedOutputStream();
                    pipelinesIn[i] = new PipedInputStream(pipelinesOut[i]);
                }

                List<Thread> pipelineThreads = new ArrayList<>();
                File currentDirFinal = currentDirectory;

                for (int i = 0; i < numStages; i++) {
                    final int stageIdx = i;
                    String[] stageParts = pipelineStages.get(stageIdx).toArray(new String[0]);
                    RedirectionResult stageRed = parseRedirections(stageParts);
                    touchRedirectionFiles(stageRed);

                    Thread stageThread = new Thread(() -> {
                        try {
                            InputStream inputSource = System.in;
                            if (stageIdx > 0) {
                                inputSource = pipelinesIn[stageIdx - 1];
                            }

                            PrintStream outputSink = System.out;
                            if (stageIdx < numStages - 1) {
                                outputSink = new PrintStream(pipelinesOut[stageIdx], true);
                            } else if (stageRed.outputFile != null) {
                                outputSink = new PrintStream(new FileOutputStream(stageRed.outputFile, stageRed.appendOutput), true);
                            }

                            if (isBuiltIn(stageRed.cleanedArgs[0])) {
                                executeBuiltIn(stageRed.cleanedArgs, inputSource, outputSink, currentDirFinal, backgroundJobs);
                                if (stageIdx < numStages - 1) {
                                    outputSink.close();
                                }
                            } else {
                                File exec = findExecutable(stageRed.cleanedArgs[0]);
                                if (exec == null) {
                                    System.err.println(stageRed.cleanedArgs[0] + ": command not found");
                                    return;
                                }

                                ProcessBuilder pb = new ProcessBuilder(stageRed.cleanedArgs).directory(currentDirFinal);
                                
                                if (stageIdx > 0) {
                                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                                } else {
                                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                                }

                                if (stageIdx < numStages - 1) {
                                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                                } else if (stageRed.outputFile != null) {
                                    pb.redirectOutput(stageRed.appendOutput ? ProcessBuilder.Redirect.appendTo(new File(stageRed.outputFile)) : ProcessBuilder.Redirect.to(new File(stageRed.outputFile)));
                                } else {
                                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                                }

                                if (stageRed.errorFile != null) {
                                    pb.redirectError(stageRed.appendError ? ProcessBuilder.Redirect.appendTo(new File(stageRed.errorFile)) : ProcessBuilder.Redirect.to(new File(stageRed.errorFile)));
                                } else {
                                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                                }

                                Process p = pb.start();

                                if (stageIdx > 0) {
                                    try (OutputStream procStdin = p.getOutputStream()) {
                                        flushTransfer(inputSource, procStdin);
                                    }
                                }

                                if (stageIdx < numStages - 1) {
                                    try (InputStream procStdout = p.getInputStream();
                                         OutputStream nextStageSink = pipelinesOut[stageIdx]) {
                                        flushTransfer(procStdout, nextStageSink);
                                    }
                                }

                                p.waitFor();
                            }
                        } catch (Exception e) {
                            // Graceful short-circuit breakdown
                        } finally {
                            try {
                                if (stageIdx > 0) pipelinesIn[stageIdx - 1].close();
                                if (stageIdx < numStages - 1) pipelinesOut[stageIdx].close();
                            } catch (IOException ignored) {}
                        }
                    });

                    pipelineThreads.add(stageThread);
                    stageThread.start();
                }

                if (!runInBackground) {
                    for (Thread t : pipelineThreads) {
                        t.join();
                    }
                }
                continue;
            }

            // --- Single Command Path ---
            RedirectionResult red = parseRedirections(parts);
            touchRedirectionFiles(red);
            parts = red.cleanedArgs;
            String cmd = parts[0];

            if (cmd.equals("exit")) break;

            if (cmd.equals("cd")) {
                if (parts.length < 2) continue;
                File target = parts[1].equals("~") ? new File(System.getenv("HOME")) : (new File(parts[1]).isAbsolute() ? new File(parts[1]) : new File(currentDirectory, parts[1]));
                if (target.exists() && target.isDirectory()) currentDirectory = target.getCanonicalFile();
                else System.out.println("cd: " + parts[1] + ": No such file or directory");
                continue;
            }

            if (isBuiltIn(cmd)) {
                PrintStream out = System.out;
                if (red.outputFile != null) out = new PrintStream(new FileOutputStream(red.outputFile, red.appendOutput), true);
                executeBuiltIn(parts, System.in, out, currentDirectory, backgroundJobs);
                if (red.outputFile != null) out.close();
                
                if (cmd.equals("jobs")) {
                    backgroundJobs.removeIf(job -> job.status.equals("Done"));
                }
                continue;
            }

            File executable = findExecutable(cmd);
            if (executable != null) {
                ProcessBuilder pb = new ProcessBuilder(parts).directory(currentDirectory);
                
                if (red.outputFile != null) {
                    pb.redirectOutput(red.appendOutput ? ProcessBuilder.Redirect.appendTo(new File(red.outputFile)) : ProcessBuilder.Redirect.to(new File(red.outputFile)));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                
                if (red.errorFile != null) {
                    pb.redirectError(red.appendError ? ProcessBuilder.Redirect.appendTo(new File(red.errorFile)) : ProcessBuilder.Redirect.to(new File(red.errorFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process process = pb.start();
                if (runInBackground) {
                    int nextJobId = backgroundJobs.isEmpty() ? 1 : backgroundJobs.stream().mapToInt(j -> j.jobId).max().getAsInt() + 1;
                    System.out.printf("[%d] %d%n", nextJobId, process.pid());
                    String cleanedCommand = command.trim();
                    if (cleanedCommand.endsWith("&")) cleanedCommand = cleanedCommand.substring(0, cleanedCommand.length() - 1).trim();
                    backgroundJobs.add(new BackgroundJob(nextJobId, process.pid(), "Running", cleanedCommand, process));
                } else {
                    process.waitFor();
                }
            } else {
                System.out.println(command + ": command not found");
            }
        }
    }
}