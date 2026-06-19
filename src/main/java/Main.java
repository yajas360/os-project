import java.io.File;
import java.io.PrintStream;
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

    private static String cleanPath(String path) {
        if (path == null) return null;
        path = path.trim();
        if (path.startsWith("'") && path.endsWith("'")) {
            return path.substring(1, path.length() - 1);
        }
        if (path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String command = scanner.nextLine();
            String outputFile = null;
            String errorFile = null;
            boolean appendOutput = false;
            boolean appendError = false;

            while (true) {
                boolean inSingle = false;
                boolean inDouble = false;
                int redirIndex = -1;
                String opFound = "";

                for (int i = 0; i < command.length(); i++) {
                    char c = command.charAt(i);
                    if (c == '\'' && !inDouble) {
                        inSingle = !inSingle;
                    } else if (c == '"' && !inSingle) {
                        inDouble = !inDouble;
                    } else if (!inSingle && !inDouble) {
                        if (command.startsWith("2>>", i)) { redirIndex = i; opFound = "2>>"; break; }
                        if (command.startsWith("1>>", i)) { redirIndex = i; opFound = "1>>"; break; }
                        if (command.startsWith(">>", i))  { redirIndex = i; opFound = ">>";  break; }
                        if (command.startsWith("2>", i))  { redirIndex = i; opFound = "2>";  break; }
                        if (command.startsWith("1>", i))  { redirIndex = i; opFound = "1>";  break; }
                        if (command.startsWith(">", i))   { redirIndex = i; opFound = ">";   break; }
                    }
                }

                if (redirIndex == -1) {
                    break;
                }

                String before = command.substring(0, redirIndex).trim();
                String after = command.substring(redirIndex + opFound.length()).trim();

                int fileEnd = after.length();
                boolean fSingle = false;
                boolean fDouble = false;
                for (int j = 0; j < after.length(); j++) {
                    char ac = after.charAt(j);
                    if (ac == '\'' && !fDouble) {
                        fSingle = !fSingle;
                    } else if (ac == '"' && !fSingle) {
                        fDouble = !fDouble;
                    } else if (Character.isWhitespace(ac) && !fSingle && !fDouble) {
                        fileEnd = j;
                        break;
                    }
                }

                String filename = after.substring(0, fileEnd).trim();
                String rest = after.substring(fileEnd).trim();

                if (opFound.equals(">") || opFound.equals("1>")) {
                    outputFile = filename;
                    appendOutput = false;
                } else if (opFound.equals(">>") || opFound.equals("1>>")) {
                    outputFile = filename;
                    appendOutput = true;
                } else if (opFound.equals("2>")) {
                    errorFile = filename;
                    appendError = false;
                } else if (opFound.equals("2>>")) {
                    errorFile = filename;
                    appendError = true;
                }

                command = (before + " " + rest).trim();
            }

            outputFile = cleanPath(outputFile);
            errorFile = cleanPath(errorFile);

            PrintStream outStream = System.out;
            PrintStream errStream = System.err;

            try {
                if (outputFile != null) {
                    File f = new File(outputFile);
                    if (f.getParentFile() != null) {
                        f.getParentFile().mkdirs();
                    }
                    outStream = new PrintStream(new java.io.FileOutputStream(f, appendOutput));
                }
                if (errorFile != null) {
                    File f = new File(errorFile);
                    if (f.getParentFile() != null) {
                        f.getParentFile().mkdirs();
                    }
                    errStream = new PrintStream(new java.io.FileOutputStream(f, appendError));
                }

                if (command.isEmpty()) {
                    continue;
                }

                if (command.equals("exit") || command.equals("exit 0")) {
                    break;
                }

                String[] parts = parseCommand(command);
                if (parts.length == 0) {
                    continue;
                }
                String baseCmd = parts[0];

                if (baseCmd.equals("cd")) {
                    String path = parts.length > 1 ? parts[1] : System.getenv("HOME");
                    if (path.startsWith("~")) {
                        path = System.getenv("HOME") + path.substring(1);
                    }

                    File dir = new File(path).isAbsolute() ? new File(path) : new File(currentDirectory, path);

                    try {
                        if (dir.exists() && dir.isDirectory()) {
                            currentDirectory = dir.getCanonicalPath();
                        } else {
                            errStream.println("cd: " + path + ": No such file or directory");
                        }
                    } catch (Exception e) {
                        errStream.println("cd: " + path + ": No such file or directory");
                    }
                    continue;
                }

                if (baseCmd.equals("pwd")) {
                    outStream.println(currentDirectory);
                    continue;
                }

                if (baseCmd.equals("echo")) {
                    StringBuilder output = new StringBuilder();
                    for (int i = 1; i < parts.length; i++) {
                        if (i > 1) {
                            output.append(" ");
                        }
                        output.append(parts[i]);
                    }
                    outStream.println(output.toString());
                    continue;
                }

                if (baseCmd.equals("type")) {
                    if (parts.length < 2) {
                        continue;
                    }
                    String cmd = parts[1];

                    if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type")
                            || cmd.equals("pwd") || cmd.equals("cd")) {
                        outStream.println(cmd + " is a shell builtin");
                        continue;
                    }

                    String pathEnv = System.getenv("PATH");
                    String[] paths = pathEnv != null ? pathEnv.split(File.pathSeparator) : new String[0];
                    boolean found = false;

                    for (String path : paths) {
                        File file = new File(path, cmd);
                        if (file.exists() && file.canExecute()) {
                            outStream.println(cmd + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }