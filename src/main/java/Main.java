import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static String[] parseCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (c == '\\') {
                if (inSingleQuotes) {
                    current.append('\\');
                } else if (inDoubleQuotes) {
                    if (i + 1 < command.length()) {
                        char next = command.charAt(i + 1);
                        if (next == '"' || next == '\\') {
                            current.append(next);
                            i++;
                        } else {
                            current.append('\\');
                        }
                    } else {
                        current.append('\\');
                    }
                } else {
                    if (i + 1 < command.length()) {
                        current.append(command.charAt(i + 1));
                        i++;
                    }
                }
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts.toArray(new String[0]);
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            String command = scanner.nextLine();
            String outputFile = null;
            String errorFile = null;

            if (command.contains("2>")) {
                String[] redirectionParts = command.split("2>", 2);
                command = redirectionParts[0].trim();
                errorFile = redirectionParts[1].trim();
            } else if (command.contains("1>")) {
                String[] redirectionParts = command.split("1>", 2);
                command = redirectionParts[0].trim();
                outputFile = redirectionParts[1].trim();
            } else if (command.contains(">")) {
                String[] redirectionParts = command.split(">", 2);
                command = redirectionParts[0].trim();
                outputFile = redirectionParts[1].trim();
            }

            if (errorFile != null) {
                try {
                    File f = new File(errorFile);
                    if (f.getParentFile() != null) {
                        f.getParentFile().mkdirs();
                    }
                    Files.writeString(f.toPath(), "");
                } catch (Exception e) {
                }
            }
            
            if (outputFile != null) {
                try {
                    File f = new File(outputFile);
                    if (f.getParentFile() != null) {
                        f.getParentFile().mkdirs();
                    }
                    Files.writeString(f.toPath(), "");
                } catch (Exception e) {
                }
            }

            if (command.equals("exit") || command.equals("exit 0")) {
                break;
            }

            if (command.startsWith("cd ")) {
                String path = command.substring(3);
                if (path.startsWith("~")) {
                    path = System.getenv("HOME") + path.substring(1);
                }

                File dir;
                if (new File(path).isAbsolute()) {
                    dir = new File(path);
                } else {
                    dir = new File(currentDirectory, path);
                }

                try {
                    if (dir.exists() && dir.isDirectory()) {
                        currentDirectory = dir.getCanonicalPath();
                    } else {
                        System.out.println("cd: " + command.substring(3) + ": No such file or directory");
                    }
                } catch (Exception e) {
                    System.out.println("cd: " + command.substring(3) + ": No such file or directory");
                }
                continue;
            }

            if (command.equals("pwd")) {
                System.out.println(currentDirectory);
                continue;
            }

            if (command.startsWith("echo")) {
                String[] parts = parseCommand(command);
                StringBuilder output = new StringBuilder();

                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) {
                        output.append(" ");
                    }
                    output.append(parts[i]);
                }

                if (outputFile != null) {
                    try {
                        Files.writeString(Paths.get(outputFile), output.toString() + System.lineSeparator());
                    } catch (Exception e) {
                    }
                } else {
                    System.out.println(output);
                }
                continue;
            }

            if (command.startsWith("type ")) {
                String cmd = command.substring(5);

                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type")
                        || cmd.equals("pwd") || cmd.equals("cd")) {
                    System.out.println(cmd + " is a shell builtin");
                    continue;
                }

                String pathEnv = System.getenv("PATH");
                String[] paths = pathEnv.split(File.pathSeparator);
                boolean found = false;

                for (String path : paths) {
                    File file = new File(path, cmd);
                    if (file.exists() && file.canExecute()) {
                        System.out.println(cmd + " is " + file.getAbsolutePath());
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    System.out.println(cmd + ": not found");
                }
                continue;
            }

            String[] parts = parseCommand(command);

            try {
                ProcessBuilder pb = new ProcessBuilder(parts);
                pb.directory(new File(currentDirectory));

                if (outputFile != null) {
                    pb.redirectOutput(new File(outputFile));
                }
                if (errorFile != null) {
                    pb.redirectError(new File(errorFile));
                }

                Process process = pb.start();

                Scanner outputScanner = new Scanner(process.getInputStream());
                while (outputScanner.hasNextLine()) {
                    System.out.println(outputScanner.nextLine());
                }

                Scanner errorScanner = new Scanner(process.getErrorStream());
                while (errorScanner.hasNextLine()) {
                    System.out.println(errorScanner.nextLine());
                }

                process.waitFor();
            } catch (Exception e) {
                System.out.println(command + ": command not found");
            }
        }
        scanner.close();
    }
}