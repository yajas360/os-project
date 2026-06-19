import java.io.File;
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
            if (c == '\'' && !inDoubleQuotes) inSingleQuotes = !inSingleQuotes;
            else if (c == '"' && !inSingleQuotes) inDoubleQuotes = !inDoubleQuotes;
            else if (c == '\\' && !inSingleQuotes) {
                if (inDoubleQuotes) {
                    if (i + 1 < command.length() && (command.charAt(i + 1) == '"' || command.charAt(i + 1) == '\\')) current.append(command.charAt(++i));
                    else current.append(c);
                } else if (i + 1 < command.length()) current.append(command.charAt(++i));
                else current.append(c);
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) { parts.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");

        while (true) {
            System.out.print("$ ");
            System.out.flush();
            if (!scanner.hasNextLine()) break;

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            boolean runInBackground = input.endsWith("&");
            if (runInBackground) input = input.substring(0, input.length() - 1).trim();

            String outputFile = null, errorFile = null;
            boolean appendOut = false, appendErr = false;

            while (true) {
                int redirIndex = -1;
                String op = "";
                for (int i = 0; i < input.length(); i++) {
                    if (input.startsWith("2>>", i)) { redirIndex = i; op = "2>>"; break; }
                    if (input.startsWith("1>>", i)) { redirIndex = i; op = "1>>"; break; }
                    if (input.startsWith(">>", i))  { redirIndex = i; op = ">>"; break; }
                    if (input.startsWith("2>", i))  { redirIndex = i; op = "2>"; break; }
                    if (input.startsWith("1>", i))  { redirIndex = i; op = "1>"; break; }
                    if (input.startsWith(">", i))   { redirIndex = i; op = ">"; break; }
                }
                if (redirIndex == -1) break;
                String before = input.substring(0, redirIndex).trim();
                String after = input.substring(redirIndex + op.length()).trim();
                int end = 0;
                while (end < after.length() && !Character.isWhitespace(after.charAt(end))) end++;
                String file = after.substring(0, end).trim();
                if (op.equals(">") || op.equals("1>")) { outputFile = file; appendOut = false; }
                else if (op.equals(">>") || op.equals("1>>")) { outputFile = file; appendOut = true; }
                else if (op.equals("2>")) { errorFile = file; appendErr = false; }
                else if (op.equals("2>>")) { errorFile = file; appendErr = true; }
                input = (before + " " + after.substring(end)).trim();
            }

            String[] parts = parseCommand(input);
            String base = parts[0];

            if (base.equals("exit")) break;
            if (base.equals("pwd")) { System.out.println(currentDirectory); continue; }
            if (base.equals("jobs")) { continue; }
            if (base.equals("cd")) {
                String path = parts.length > 1 ? parts[1].replace("~", System.getenv("HOME")) : System.getenv("HOME");
                File dir = new File(path).isAbsolute() ? new File(path) : new File(currentDirectory, path);
                if (dir.exists() && dir.isDirectory()) currentDirectory = dir.getAbsolutePath();
                else System.err.println("cd: " + path + ": No such file or directory");
                continue;
            }
            if (base.equals("echo")) {
                for (int i = 1; i < parts.length; i++) System.out.print(parts[i] + (i == parts.length - 1 ? "" : " "));
                System.out.println();
                continue;
            }
            if (base.equals("type")) {
                if (parts.length < 2) continue;
                String cmd = parts[1];
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("jobs")) System.out.println(cmd + " is a shell builtin");
                else {
                    boolean found = false;
                    for (String p : System.getenv("PATH").split(File.pathSeparator)) {
                        File f = new File(p, cmd);
                        if (f.exists() && f.canExecute()) { System.out.println(cmd + " is " + f.getAbsolutePath()); found = true; break; }
                    }
                    if (!found) System.out.println(cmd + ": not found");
                }
                continue;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(parts);
                pb.directory(new File(currentDirectory));
                if (outputFile != null) pb.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(new File(outputFile)) : ProcessBuilder.Redirect.to(new File(outputFile)));
                else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                
                if (errorFile != null) pb.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(new File(errorFile)) : ProcessBuilder.Redirect.to(new File(errorFile)));
                else pb.redirectError(ProcessBuilder.Redirect.INHERIT); // Ensure stderr is shown in terminal
                
                Process p = pb.start();
                if (runInBackground) System.out.println("[1] " + p.pid());
                else p.waitFor();
            } catch (Exception e) {
                System.out.println(base + ": command not found");
            }
        }
    }
}