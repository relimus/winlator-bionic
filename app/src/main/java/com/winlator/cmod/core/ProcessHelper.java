package com.winlator.cmod.core;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

public abstract class ProcessHelper {
    public static final boolean PRINT_DEBUG = true; // FIXME change to false
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();
    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;
    private static final byte SIGTERM = 15;
    private static final byte SIGKILL = 9;
    private static volatile boolean useRootForSignals = false;
    private static volatile String wineProcessEnvFilter = "";
    private static final Object suspendedWineProcessesLock = new Object();
    private static final ArrayList<String> suspendedWineProcesses = new ArrayList<>();

    private static volatile boolean hasUsedRootSession = false;

    public static void setUseRootForSignals(boolean enabled) {
        useRootForSignals = enabled;
    }

    public static void setWineProcessEnvFilter(String winePrefix) {
        wineProcessEnvFilter = winePrefix != null && !winePrefix.isEmpty() ? "WINEPREFIX=" + winePrefix : "";
    }

    public static boolean hasUsedRootSession() {
        return hasUsedRootSession;
    }
    public static void suspendProcess(int pid) {
        sendSignal(pid, SIGSTOP);
        Log.d("ProcessHelper", "Process suspended with pid: " + pid);
    }

    public static void resumeProcess(int pid) {
        sendSignal(pid, SIGCONT);
        Log.d("ProcessHelper", "Process resumed with pid: " + pid);
    }

    public static void terminateProcess(int pid) {
        sendSignal(pid, SIGTERM);
        Log.d("ProcessHelper", "Process terminated with pid: " + pid);
    }

    public static void killProcess(int pid) {
        sendSignal(pid, SIGKILL);
        Log.d("ProcessHelper", "Process killed with pid: " + pid);
    }

    private static void sendSignal(int pid, int signal) {
        if (useRootForSignals) {
            execRootCommandAndWait("kill -" + signal + " " + pid);
            return;
        }
        Process.sendSignal(pid, signal);
    }

    public static void terminateAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            terminateProcess(Integer.parseInt(process));
        }
    }

    public static void killAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            killProcess(Integer.parseInt(process));
        }
    }

    public static boolean waitForWineProcessesExit(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (!listRunningWineProcesses().isEmpty()) {
            if (System.currentTimeMillis() - start >= timeoutMs) return false;
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public static void pauseAllWineProcesses() {
        pauseAllWineProcesses(() -> true);
    }

    public static void pauseAllWineProcesses(BooleanSupplier shouldPause) {
        if (useRootForSignals) {
            ArrayList<String> processes = listRunningWineProcessesAsRoot();
            if (!shouldPause.getAsBoolean()) return;

            synchronized (suspendedWineProcessesLock) {
                suspendedWineProcesses.clear();
                suspendedWineProcesses.addAll(processes);
            }
            signalWineProcessesAsRoot(processes, SIGSTOP);
            return;
        }

        if (!shouldPause.getAsBoolean()) return;
        for (String process : listRunningWineProcesses()) {
            suspendProcess(Integer.parseInt(process));
        }
    }

    public static void resumeAllWineProcesses() {
        if (useRootForSignals) {
            ArrayList<String> processes;
            synchronized (suspendedWineProcessesLock) {
                processes = new ArrayList<>(suspendedWineProcesses);
            }

            if (signalWineProcessesAsRoot(processes, SIGCONT) == 0) {
                synchronized (suspendedWineProcessesLock) {
                    suspendedWineProcesses.removeAll(processes);
                }
            }
            return;
        }

        for (String process : listRunningWineProcesses()) {
            resumeProcess(Integer.parseInt(process));
        }
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, String[] envp) {
        return exec(command, envp, null);
    }

    public static int exec(String command, String[] envp, File workingDir) {
        return exec(command, envp, workingDir, null);
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        Log.d("ProcessHelper", "env: " + Arrays.toString(envp) + "\ncmd: " + command);

        // Store env vars for future use
        EnvironmentManager.setEnvVars(envp);

        int pid = -1;
        try {
            Log.d("ProcessHelper", "Splitting command: " + command);
            String[] splitCommand = splitCommand(command);
            Log.d("ProcessHelper", "Split command result: " + Arrays.toString(splitCommand));
            Log.d("ProcessHelper", "Starting process...");
            ProcessBuilder pb = new ProcessBuilder(splitCommand);
            pb.directory(workingDir);
            pb.environment().putAll(EnvironmentManager.getEnvVars());
            if (debugCallbacks.isEmpty()) {
                File null_file = new File("/dev/null");
                pb.redirectError(null_file);
                pb.redirectOutput(null_file);
            }
            java.lang.Process process = pb.start();

            // Accessing hidden field
            Log.d("ProcessHelper", "Accessing hidden field to get PID");
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);
            Log.d("ProcessHelper", "Process started with pid: " + pid);

            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }

            if (terminationCallback != null) createWaitForThread(process, terminationCallback);

        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Error executing command: " + command, e);
        }
        return pid;
    }

    public static int execAsRoot(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        Log.d("ProcessHelper", "root env: " + Arrays.toString(envp) + "\ncmd: " + command);

        EnvironmentManager.setEnvVars(envp);

        int pid = -1;
        try {
            File pidFile = createRootPidFile(workingDir);
            java.lang.Process process = Runtime.getRuntime().exec("su");
            hasUsedRootSession = true;
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            if (envp != null) {
                for (String entry : envp) {
                    int index = entry.indexOf('=');
                    if (index <= 0) continue;
                    String name = entry.substring(0, index);
                    String value = entry.substring(index + 1);
                    if (!isValidEnvName(name)) continue;
                    writer.write("export " + name + "=" + shellQuote(value) + "\n");
                }
            }

            if (workingDir != null) {
                writer.write("cd " + shellQuote(workingDir.getAbsolutePath()) + "\n");
            }
            writer.write(command + " &\n");
            writer.write("child=$!\n");
            writer.write("echo $child > " + shellQuote(pidFile.getAbsolutePath()) + "\n");
            writer.write("wait $child\n");
            writer.write("exit $?\n");
            writer.flush();
            writer.close();

            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);

            attachOutputThreads(process);
            int childPid = waitForPidFile(pidFile);
            if (childPid != -1) pid = childPid;

            createWaitForThread(process, (status) -> {
                pidFile.delete();
                if (terminationCallback != null) terminationCallback.call(status);
            });
        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Error executing root command: " + command, e);
        }
        return pid;
    }

    public static boolean isRootAvailable() {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream outputStream = new DataOutputStream(process.getOutputStream());
            outputStream.writeBytes("id -u\n");
            outputStream.writeBytes("exit\n");
            outputStream.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String uid = reader.readLine();
            int status = process.waitFor();
            boolean available = status == 0 && "0".equals(uid != null ? uid.trim() : "");
            if (available) hasUsedRootSession = true;
            return available;
        }
        catch (Exception e) {
            Log.w("ProcessHelper", "Root is not available", e);
            return false;
        }
        finally {
            if (process != null) process.destroy();
        }
    }

    public static boolean chownAsRoot(String path) {
        return chown(path, "0:0");
    }

    public static boolean chownAsAppUser(String path) {
        int uid = Process.myUid();
        return chown(path, uid + ":" + uid);
    }

    private static boolean chown(String path, String owner) {
        if (path == null || path.isEmpty()) return false;
        return execRootCommandAndWait("chown -R " + shellQuote(owner) + " " + shellQuote(path)) == 0;
    }

    private static int execRootCommandAndWait(String command) {
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            attachOutputThreads(process);
            DataOutputStream outputStream = new DataOutputStream(process.getOutputStream());
            outputStream.writeBytes(command + "\n");
            outputStream.writeBytes("exit\n");
            outputStream.flush();
            int status = process.waitFor();
            if (status == 0) hasUsedRootSession = true;
            return status;
        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Error executing root shell command: " + command, e);
            return -1;
        }
        finally {
            if (process != null) process.destroy();
        }
    }

    private static ArrayList<String> execRootCommandAndReadLines(String command) {
        ArrayList<String> result = new ArrayList<>();
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            createDrainThread(process.getErrorStream());
            DataOutputStream outputStream = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            outputStream.writeBytes(command + "\n");
            outputStream.writeBytes("exit\n");
            outputStream.flush();
            outputStream.close();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) result.add(line);
            }
            reader.close();

            if (process.waitFor() == 0) hasUsedRootSession = true;
        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Error executing root shell command: " + command, e);
        }
        finally {
            if (process != null) process.destroy();
        }
        return result;
    }

    private static File createRootPidFile(File workingDir) throws IOException {
        File pidFile = File.createTempFile("winlator-root-", ".pid", workingDir);
        FileUtils.chmod(pidFile, 0666);
        return pidFile;
    }

    private static int waitForPidFile(File pidFile) {
        for (int i = 0; i < 100; i++) {
            try {
                byte[] data = FileUtils.read(pidFile);
                String pid = data != null ? new String(data, StandardCharsets.UTF_8).trim() : "";
                if (!pid.isEmpty()) return Integer.parseInt(pid);
                Thread.sleep(20);
            }
            catch (NumberFormatException e) {
                return -1;
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    private static boolean isValidEnvName(String name) {
        if (name == null || name.isEmpty()) return false;
        char first = name.charAt(0);
        if (!(first == '_' || Character.isLetter(first))) return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c == '_' || Character.isLetterOrDigit(c))) return false;
        }
        return true;
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void attachOutputThreads(java.lang.Process process) {
        if (!debugCallbacks.isEmpty()) {
            createDebugThread(process.getInputStream());
            createDebugThread(process.getErrorStream());
        }
        else {
            createDrainThread(process.getInputStream());
            createDrainThread(process.getErrorStream());
        }
    }

    private static void createDrainThread(final InputStream inputStream) {
        new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                while (inputStream.read(buffer) != -1) {}
            }
            catch (IOException e) {
                Log.e("ProcessHelper", "Error draining process output", e);
            }
            finally {
                try {
                    inputStream.close();
                }
                catch (IOException e) {}
            }
        }).start();
    }

    private static void createDebugThread(final InputStream inputStream) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PRINT_DEBUG) System.out.println(line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                    }
                }
            }
            catch (IOException e) {
                Log.e("ProcessHelper", "Error in debug thread", e);
            }
        }).start();
    }

    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int status = process.waitFor();
                    terminationCallback.call(status);
                }
                catch (InterruptedException e) {
                    Log.e("ProcessHelper", "Error waiting for process termination", e);
                }
            }
        }).start();
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
            Log.d("ProcessHelper", "All debug callbacks removed");
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
            Log.d("ProcessHelper", "Added debug callback: " + callback.toString());
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
            Log.d("ProcessHelper", "Removed debug callback: " + callback.toString());
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static ArrayList<String> listRunningWineProcesses(){
        if (useRootForSignals) return listRunningWineProcessesAsRoot();

        File proc = new File("/proc");
        String[] filters = {"wine", "exe"};
        String[] allPids;
        ArrayList<String> filteredPids = new ArrayList<String>();
        List<String> filterList = Arrays.asList(filters);
        allPids = proc.list(new FilenameFilter(){
            public boolean accept(File proc, String filename){
                return new File(proc, filename).isDirectory() && filename.matches("[0-9]+");
            }
        });

        for (int index = 0; index < allPids.length; index++){
            String data = "";
            try {
                FileInputStream fr = new FileInputStream(proc + "/" + allPids[index] + "/stat");
                BufferedReader br = new BufferedReader(new InputStreamReader(fr));
                data = br.readLine();
            }
            catch (IOException e) {}
            for (String filter : filterList) {
                if (data.contains(filter))
                    filteredPids.add(allPids[index]);
            }
        }
        return filteredPids;
    }

    private static int signalWineProcessesAsRoot(List<String> processes, int signal) {
        if (wineProcessEnvFilter.isEmpty() || processes.isEmpty()) return 0;

        StringBuilder pids = new StringBuilder();
        for (String process : processes) {
            if (process.matches("[0-9]+")) pids.append(' ').append(process);
        }
        if (pids.length() == 0) return 0;

        int status = execRootCommandAndWait(
                "for pid in" + pids + "; do " +
                "data=$(cat \"/proc/$pid/stat\" 2>/dev/null) || continue; " +
                "case \"$data\" in *wine*|*exe*) ;; *) continue;; esac; " +
                "tr '\\000' '\\n' < \"/proc/$pid/environ\" 2>/dev/null | grep -Fxq " + shellQuote(wineProcessEnvFilter) + " || continue; " +
                "kill -" + signal + " \"$pid\" 2>/dev/null; " +
                "done"
        );

        if (status != 0)
            Log.w("ProcessHelper", "Failed to send signal " + signal + " to root Wine processes, status=" + status);
        return status;
    }

    private static ArrayList<String> listRunningWineProcessesAsRoot() {
        ArrayList<String> filteredPids = new ArrayList<>();
        if (wineProcessEnvFilter.isEmpty()) return filteredPids;

        ArrayList<String> lines = execRootCommandAndReadLines(
                "for pid in /proc/[0-9]*; do " +
                "data=$(cat \"$pid/stat\" 2>/dev/null) || continue; " +
                "case \"$data\" in *wine*|*exe*) ;; *) continue;; esac; " +
                "tr '\\000' '\\n' < \"$pid/environ\" 2>/dev/null | grep -Fxq " + shellQuote(wineProcessEnvFilter) + " || continue; " +
                "echo \"${pid#/proc/}\"; " +
                "done"
        );

        for (String line : lines) {
            if (line.matches("[0-9]+") && !filteredPids.contains(line))
                filteredPids.add(line);
        }
        return filteredPids;
    }
}
