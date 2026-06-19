import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            String command = scanner.nextLine();

            if (command.equals("exit") || command.equals("exit 0")) {
                break;
            }

            if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
                continue;
            }

            if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
                continue;
            }

            if (command.startsWith("type ")) {
                String cmd = command.substring(5);

                if (cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") || cmd.equals("pwd")) {
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

            String[] parts = command.split(" ");

            try {
                ProcessBuilder pb = new ProcessBuilder(parts);
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