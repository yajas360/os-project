import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static String[] parseCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\'') {
                inSingleQuotes = !inSingleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes) {
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

                for (int i = 1; i < parts.length; i++) {
                    if (i > 1) {
                        System.out.print(" ");
                    }
                    System.out.print(parts[i]);
                }

                System.out.println();
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
                pb.redirectErrorStream(true);

                Process process = pb.start();

                Scanner outputScanner = new Scanner(process.getInputStream());

                while (outputScanner.hasNextLine()) {
                    System.out.println(outputScanner.nextLine());
                }

                process.waitFor();
            } catch (Exception e) {
                System.out.println(command + ": command not found");
            }
        }

        scanner.close();
    }
}