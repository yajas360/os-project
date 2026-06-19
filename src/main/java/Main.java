import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static void writeOutput(String text, String file, boolean append, boolean isError) throws IOException {
        if (file != null) {
            File f = new File(file);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileWriter fw = new FileWriter(f, append)) {
                fw.write(text + System.lineSeparator());
            }
        } else {
            if (isError) System.err.println(text);
            else System.out.println(text);
        }
    }

    private static String[] parseCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '\\' && !inSingle) {
                if (inDouble) {
                    if (i + 1 < command.length() && (command.charAt(i + 1) == '"' || command.charAt(i + 1) == '\\')) current.append(command.charAt(++i));
                    else current.append(c);
                } else if (i + 1 < command.length()) current.append(command.charAt(++i));
                else current.append(c);
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) { parts.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String dir = System.getProperty("user.dir");

        while (true) {
            System.out.print("$ ");
            System.out.flush();
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            boolean runBg = input.endsWith("&");
            if (runBg) input = input.substring(0, input.length() - 1).trim();

            String outF = null, errF = null;
            boolean appOut = false, appErr = false;

            while (true) {
                int idx = -1; String op = "";
                for (int i = 0; i < input.length(); i++) {
                    if (input.startsWith("2>>", i)) { idx = i; op = "2>>"; break; }
                    if (input.startsWith("1>>", i)) { idx = i; op = "1>>"; break; }
                    if (input.startsWith(">>", i))  { idx = i; op = ">>"; break; }
                    if (input.startsWith("2>", i))  { idx = i; op = "2>"; break; }
                    if (input.startsWith("1>", i))  { idx = i; op = "1>"; break; }
                    if (input.startsWith(">", i))   { idx = i; op = ">"; break; }
                }
                if (idx == -1) break;
                String before = input.substring(0, idx).trim();
                String after = input.substring(idx + op.length()).trim();
                int end = 0; while (end < after.length() && !Character.isWhitespace(after.charAt(end))) end++;
                String f = after.substring(0, end).trim();
                if (op.equals(">") || op.equals("1>")) { outF = f; appOut = false; }
                else if (op.equals(">>") || op.equals("1>>")) { outF = f; appOut = true; }
                else if (op.equals("2>")) { errF = f; appErr = false; }
                else if (op.equals("2>>")) { errF = f; appErr = true; }
                input = (before + " " + after.substring(end)).trim();
            }

            String[] p = parseCommand(input);
            String b = p[0];

            if (outF != null) {
                File f = new File(outF);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                if (!f.exists()) f.createNewFile();
            }
            if (errF != null) {
                File f = new File(errF);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                if (!f.exists()) f.createNewFile();
            }

            if (b.equals("exit")) break;
            if (b.equals("pwd")) { writeOutput(dir, outF, appOut, false); continue; }
            if (b.equals("jobs")) continue;
            if (b.equals("cd")) {
                String path = p.length > 1 ? p[1].replace("~", System.getenv("HOME")) : System.getenv("HOME");
                File d = new File(path).isAbsolute() ? new File(path) : new File(dir, path);
                if (d.exists() && d.isDirectory()) dir = d.getCanonicalPath();
                else writeOutput("cd: " + path + ": No such file or directory", errF, appErr, true);
                continue;
            }
            if (b.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < p.length; i++) sb.append(p[i]).append(i == p.length - 1 ? "" : " ");
                writeOutput(sb.toString(), outF, appOut, false);
                continue;
            }
            if (b.equals("type")) {
                if (p.length < 2) continue;
                String cmd = p[1];
                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("jobs")) 
                    writeOutput(cmd + " is a shell builtin", outF, appOut, false);
                else {
                    boolean found = false;
                    for (String path : System.getenv("PATH").split(File.pathSeparator)) {
                        File f = new File(path, cmd);
                        if (f.exists() && f.canExecute()) { writeOutput(cmd + " is " + f.getAbsolutePath(), outF, appOut, false); found = true; break; }
                    }
                    if (!found) writeOutput(cmd + ": not found", outF, appOut, false);
                }
                continue;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(p);
                pb.directory(new File(dir));
                if (outF != null) pb.redirectOutput(appOut ? ProcessBuilder.Redirect.appendTo(new File(outF)) : ProcessBuilder.Redirect.to(new File(outF)));
                else pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                if (errF != null) pb.redirectError(appErr ? ProcessBuilder.Redirect.appendTo(new File(errF)) : ProcessBuilder.Redirect.to(new File(errF)));
                else pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                Process pr = pb.start();
                if (runBg) System.out.println("[1] " + pr.pid());
                else pr.waitFor();
            } catch (Exception e) {
                System.out.println(b + ": command not found");
            }
        }
    }
}