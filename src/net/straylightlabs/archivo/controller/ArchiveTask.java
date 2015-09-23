/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.archivo.controller;

import javafx.application.Platform;
import javafx.concurrent.Task;
import net.straylightlabs.archivo.Archivo;
import net.straylightlabs.archivo.UserPrefs;
import net.straylightlabs.archivo.model.ArchiveStatus;
import net.straylightlabs.archivo.model.EditDecisionList;
import net.straylightlabs.archivo.model.Recording;
import net.straylightlabs.archivo.model.Tivo;
import net.straylightlabs.archivo.net.MindCommandIdSearch;
import net.straylightlabs.tivolibre.TivoDecoder;
import org.apache.http.Header;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Handle the tasks of fetching the recording file from a TiVo, decrypting it, and transcoding it.
 */
public class ArchiveTask extends Task<Recording> {
    private final Recording recording;
    private final Tivo tivo;
    private final String mak;
    private final UserPrefs prefs;
    private Path downloadPath; // downloaded file
    private Path fixedPath; // re-muxed file
    private Path cutPath; // comskipped file

    private static final int BUFFER_SIZE = 8192; // 8 KB
    private static final int PIPE_BUFFER_SIZE = 1024 * 1024 * 16; // 16 MB
    private static final int MIN_PROGRESS_INCREMENT = 10 * 1024 * 1024; // 10 MB, number of bytes that must transfer before we update our progress
    private static final int NUM_RETRIES = 5; // number of times to retry a failed download
    private static final int RETRY_DELAY = 5000; // delay between retry attempts, in ms
    private static final double ESTIMATED_SIZE_THRESHOLD = 0.8; // Need to download this % of a file to consider it successful

    public ArchiveTask(Recording recording, Tivo tivo, String mak, final UserPrefs prefs) {
        this.recording = recording;
        this.tivo = tivo;
        this.mak = mak;
        this.prefs = prefs;
    }

    @Override
    protected Recording call() throws ArchiveTaskException {
        archive();
        return recording;
    }

    private void archive() throws ArchiveTaskException {
        if (isCancelled()) {
            Archivo.logger.info("ArchiveTask canceled by user.");
            return;
        }

        Archivo.logger.info("Starting archive task for {}", recording.getTitle());
        try {
            MindCommandIdSearch command = new MindCommandIdSearch(recording, tivo);
//            command.setCompatibilityMode(true); // Download a PS file instead of a TS file
            command.executeOn(tivo.getClient());
            URL url = command.getDownloadUrl();
            Archivo.logger.info("URL: {}", url);
            downloadPath = buildPath(recording.getDestination(), "download.ts");
            Archivo.logger.info("Saving file to {}", downloadPath);
            getRecording(recording, url);
            if (shouldDecrypt(recording)) {
                // TODO For each of these commands:
                // 0) if the file already exists, delete it
                // 1) use the task thread to check for task cancellation

                remux();
                if (prefs.getSkipCommercials()) {
                    detectCommercials();
                    cutCommercials();
                }
                transcode();
                cleanupFiles(fixedPath);
            }
        } catch (IOException e) {
            Archivo.logger.error("Error fetching recording information: ", e);
            throw new ArchiveTaskException("Problem fetching recording information");
        }
    }

    private void getRecording(Recording recording, URL url) throws ArchiveTaskException {
        if (isCancelled()) {
            Archivo.logger.info("ArchiveTask canceled by user.");
            return;
        }

        try (CloseableHttpClient client = buildHttpClient()) {
            HttpGet get = new HttpGet(url.toString());
            // Initial request to set the session cookie
            try (CloseableHttpResponse response = client.execute(get)) {
                response.close(); // Not needed, but clears up a warning
            }
            // Now fetch the file
            int retries = NUM_RETRIES;
            int retryDelay = RETRY_DELAY;
            boolean responseOk = false;
            while (!responseOk && retries > 0) {
                try (CloseableHttpResponse response = client.execute(get)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        Archivo.logger.error("Error downloading recording: {}", response.getStatusLine());
                        Archivo.logger.info("Sleeping for {} ms", retryDelay);
                        Thread.sleep(retryDelay);
                        retryDelay += RETRY_DELAY;
                        retries--;
                    } else {
                        Archivo.logger.info("Status line: {}", response.getStatusLine());
                        responseOk = true;
                        handleResponse(response, recording);
                    }
                } catch (InterruptedException e) {
                    Archivo.logger.error("Thread interrupted: ", e);
                }
            }
            if (!responseOk) {
                throw new ArchiveTaskException("Problem downloading recording");
            }
        } catch (IOException e) {
            Archivo.logger.error("Error downloading recording: ", e);
            throw new ArchiveTaskException("Problem downloading recording");
        }
    }

    private CloseableHttpClient buildHttpClient() {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
                new UsernamePasswordCredentials("tivo", mak));
        CookieStore cookieStore = new BasicCookieStore();
        ConnectionConfig connConfig = ConnectionConfig.custom().setBufferSize(BUFFER_SIZE).build();
        return HttpClients.custom()
                .useSystemProperties()
                .setDefaultConnectionConfig(connConfig)
                .setDefaultCredentialsProvider(credsProvider)
                .setDefaultCookieStore(cookieStore)
                .setUserAgent(Archivo.USER_AGENT)
                .build();
    }

    private void handleResponse(CloseableHttpResponse response, Recording recording) throws ArchiveTaskException {
        long estimatedLength = getEstimatedLengthFromHeaders(response);
        boolean decrypt = shouldDecrypt(recording);
        try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(downloadPath));
             BufferedInputStream inputStream = new BufferedInputStream(response.getEntity().getContent(), BUFFER_SIZE);
             PipedInputStream pipedInputStream = new PipedInputStream(PIPE_BUFFER_SIZE);
             PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream)
        ) {
            Archivo.logger.info("Starting download...");
            // Pipe the network stream to our TiVo decoder, then pipe the output of that to the output file stream
            Thread thread = null;
            if (decrypt) {
                thread = new Thread(() -> {
                    TivoDecoder decoder = new TivoDecoder.Builder().input(pipedInputStream).output(outputStream).mak(mak).build();
                    if (!decoder.decode()) {
                        Archivo.logger.error("Failed to decode file");
                        throw new ArchiveTaskException("Problem decoding recording");
                    }
                });
                thread.start();
            }

            byte[] buffer = new byte[BUFFER_SIZE + 1];
            long totalBytesRead = 0;
            long priorBytesRead = 0;
            LocalDateTime startTime = LocalDateTime.now();
            for (int bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE);
                 bytesRead >= 0;
                 bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE)) {
                if (decrypt && !thread.isAlive()) {
                    Archivo.logger.error("Decoding thread died prematurely");
                    response.close();
                    throw new ArchiveTaskException("Problem decoding recording");
                }
                if (isCancelled()) {
                    Archivo.logger.info("ArchiveTask cancelled by user.");
                    response.close(); // Stop the network transaction
                    return;
                }
                totalBytesRead += bytesRead;
                Archivo.logger.trace("Bytes read: {}", bytesRead);

                if (decrypt) {
                    pipedOutputStream.write(buffer, 0, bytesRead);
                } else {
                    outputStream.write(buffer, 0, bytesRead);
                }

                if (totalBytesRead > priorBytesRead + MIN_PROGRESS_INCREMENT) {
                    double percent = totalBytesRead / (double) estimatedLength;
                    updateProgress(recording, percent, startTime, totalBytesRead, estimatedLength);
                    priorBytesRead = totalBytesRead;
                    Archivo.logger.info("Total bytes read from network: {}", totalBytesRead);
                }
            }
            Archivo.logger.info("Download finished.");

            if (decrypt) {
                // Close the pipe to ensure the decoding thread finishes
                pipedOutputStream.flush();
                pipedOutputStream.close();
                // Wait for the decoding thread to finish
                thread.join();
                Archivo.logger.info("Decoding finished.");
            }

            verifyDownloadSize(totalBytesRead, estimatedLength);
        } catch (IOException e) {
            Archivo.logger.error("IOException while downloading recording: ", e);
            throw new ArchiveTaskException("Problem downloading recording");
        } catch (InterruptedException e) {
            Archivo.logger.error("Decoding thread interrupted: ", e);
        }
    }

    /**
     * If the user chose to save the file as a .TiVo, don't decrypt it.
     */
    private boolean shouldDecrypt(Recording recording) {
        boolean decrypt = !recording.getDestination().toString().endsWith(".TiVo");
        Archivo.logger.info("decrypt = {}", decrypt);
        return decrypt;
    }

    private long getEstimatedLengthFromHeaders(CloseableHttpResponse response) {
        long length = -1;
        for (Header header : response.getAllHeaders()) {
            if (header.getName().equalsIgnoreCase("TiVo-Estimated-Length")) {
                try {
                    length = Long.parseLong(header.getValue());
                } catch (NumberFormatException e) {
                    Archivo.logger.error("Error parsing estimated length ({}): ", header.getValue(), e);
                }
            }
        }
        return length;
    }

    private void updateProgress(Recording recording, double percent, LocalDateTime startTime, long totalBytesRead, long estimatedLength) {
        Duration elapsedTime = Duration.between(startTime, LocalDateTime.now());
        try {
            double kbs = (totalBytesRead / 1024) / elapsedTime.getSeconds();
            long kbRemaining = (estimatedLength - totalBytesRead) / 1024;
            int secondsRemaining = (int) (kbRemaining / kbs);
            Archivo.logger.info(String.format("Read %d bytes of %d expected bytes (%d%%) in %s (%.1f KB/s)",
                    totalBytesRead, estimatedLength, (int) (percent * 100), elapsedTime, kbs));
            Platform.runLater(() -> recording.statusProperty().setValue(
                    ArchiveStatus.createDownloadingStatus(percent, secondsRemaining)
            ));
        } catch (ArithmeticException e) {
            Archivo.logger.warn("ArithmeticException: ", e);
        }
    }

    private void verifyDownloadSize(long bytesRead, long bytesExpected) {
        if (bytesRead / (double) bytesExpected < ESTIMATED_SIZE_THRESHOLD) {
            Archivo.logger.error("Failed to download file ({} bytes read, {} bytes expected)", bytesRead, bytesExpected);
            throw new ArchiveTaskException("Failed to download recording");
        }
    }

    /**
     * TiVo files often have timestamp problems. Remux to fix them.
     */
    private void remux() {
        Platform.runLater(() -> recording.statusProperty().setValue(
                        ArchiveStatus.createRemuxingStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        String ffmpegPath = prefs.getFfmpegPath();
        fixedPath = buildPath(recording.getDestination(), "fixed.ts");
        Archivo.logger.info("ffmpeg path = {} outputPath = {}", ffmpegPath, fixedPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-i");
        cmd.add(downloadPath.toString());
        cmd.add("-codec");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("mpegts");
        cmd.add(fixedPath.toString());
        Archivo.logger.info("Remux command: {}", cmd);
        Archivo.logger.info("Current directory: {}", System.getProperty("user.dir"));
        try {
            FfmpegOutputReader outputReader = new FfmpegOutputReader(recording, ArchiveStatus.TaskStatus.REMUXING);
            runProcess(cmd, outputReader);
        } catch (InterruptedException | IOException e) {
            Archivo.logger.error("Error running ffmpeg to remux download: ", e);
            throw new ArchiveTaskException("Error remuxing recording");
        } finally {
            cleanupFiles(downloadPath);
        }
    }

    private void detectCommercials() {
        Platform.runLater(() -> recording.statusProperty().setValue(
                        ArchiveStatus.createFindingCommercialsStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        String comskipPath = prefs.getComskipPath();
        String comskipIniPath = Paths.get(Paths.get(comskipPath).getParent().toString(), "comskip.ini").toString();
        Path logoPath = buildPath(fixedPath, "logo.txt");
        List<String> cmd = new ArrayList<>();
        cmd.add(comskipPath);
        cmd.add("--ini");
        cmd.add(comskipIniPath);
        cmd.add("--logo");
        cmd.add(logoPath.toString());
        cmd.add("--ts");
        cmd.add(fixedPath.toString());
        cmd.add(fixedPath.getParent().toString());
        Archivo.logger.info("Comskip command: {}", cmd);
        try {
            ComskipOutputReader outputReader = new ComskipOutputReader(recording);
            runProcess(cmd, outputReader);
        } catch (InterruptedException | IOException e) {
            Archivo.logger.error("Error running comskip: ", e);
            throw new ArchiveTaskException("Error finding commercials");
        } finally {
            cleanupFiles(logoPath);
        }
    }

    private void cutCommercials() {
        Platform.runLater(() -> recording.statusProperty().setValue(
                        ArchiveStatus.createRemovingCommercialsStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        cutPath = buildPath(recording.getDestination(), "cut.ts");
        Path edlPath = buildPath(fixedPath, "edl");
        int filePartCounter = 1;
        String ffmpegPath = prefs.getFfmpegPath();
        Path partList = buildPath(fixedPath, "parts");
        List<Path> partPaths = new ArrayList<>();
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(partList))) {
            EditDecisionList edl = EditDecisionList.createFromFile(edlPath);
            List<String> cmdPrototype = new ArrayList<>();
            cmdPrototype.add(ffmpegPath);
            cmdPrototype.add("-i");
            cmdPrototype.add(fixedPath.toString());
            cmdPrototype.add("-codec");
            cmdPrototype.add("copy");

            Archivo.logger.info("EDL: {}", edl);
            List<EditDecisionList.Segment> toKeep = edl.getSegmentsToKeep();
            int curSegment = 1;
            Path escapedPath = Paths.get(fixedPath.toString().replace("\'", ""));
            for (EditDecisionList.Segment segment : toKeep) {
                List<String> cmd = new ArrayList<>(cmdPrototype);
                cmd.addAll(segment.buildFfmpegCutParamList().stream().collect(Collectors.toList()));
                Path partPath = buildPath(escapedPath, String.format("part%02d.ts", filePartCounter++));
                writer.println(String.format("file '%s'", partPath.toString().replace("'", "\\'")));
                partPaths.add(partPath);
                cmd.add(partPath.toString());

                Archivo.logger.info("ffmpeg command: {}", cmd);
                try {
                    FfmpegOutputReader outputReader = new FfmpegOutputReader(recording, ArchiveStatus.TaskStatus.NONE);
                    runProcess(cmd, outputReader);
                    Platform.runLater(() -> recording.statusProperty().setValue(
                                    ArchiveStatus.createRemovingCommercialsStatus(curSegment / (double) toKeep.size(), ArchiveStatus.TIME_UNKNOWN))
                    );
                } catch (InterruptedException | IOException e) {
                    Archivo.logger.error("Error running ffmpeg to cut commercials: ", e);
                    throw new ArchiveTaskException("Error removing commercials");
                }
            }
        } catch (IOException e) {
            Archivo.logger.error("Error reading EDL file '{}': ", edlPath, e);
            cleanupFiles(partList);
            cleanupFiles(partPaths);
            return;
        } finally {
            cleanupFiles(edlPath);
        }

        Platform.runLater(() -> recording.statusProperty().setValue(
                        ArchiveStatus.createRemovingCommercialsStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-f");
        cmd.add("concat");
        cmd.add("-i");
        cmd.add(partList.toString());
        cmd.add("-codec");
        cmd.add("copy");
        cmd.add(cutPath.toString());
        Archivo.logger.info("ffmpeg command: {}", cmd);
        try {
            FfmpegOutputReader outputReader = new FfmpegOutputReader(recording, ArchiveStatus.TaskStatus.REMOVING_COMMERCIALS);
            runProcess(cmd, outputReader);
        } catch (InterruptedException | IOException e) {
            Archivo.logger.error("Error running ffmpeg to join files: ", e);
            throw new ArchiveTaskException("Error removing commercials");
        } finally {
            cleanupFiles(partList);
            cleanupFiles(partPaths);
        }
    }

    private void transcode() {
        Platform.runLater(() -> recording.statusProperty().setValue(
                        ArchiveStatus.createTranscodingStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        String handbrakePath = prefs.getHandbrakePath();
        Path sourcePath = cutPath;
        if (sourcePath == null) {
            sourcePath = fixedPath;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(handbrakePath);
        cmd.add("-i");
        cmd.add(sourcePath.toString());
        cmd.add("-o");
        cmd.add(recording.getDestination().toString());
        cmd.add("--preset");
        cmd.add("Normal");
        cmd.add("-5");
        Archivo.logger.info("HandBrake command: {}", cmd);
        try {
            HandbrakeOutputReader outputReader = new HandbrakeOutputReader(recording);
            runProcess(cmd, outputReader);
        } catch (InterruptedException | IOException e) {
            Archivo.logger.error("Error running HandBrake: ", e);
            throw new ArchiveTaskException("Error running HandBrake");
        } finally {
            cleanupFiles(cutPath);
        }
    }

    private boolean runProcess(List<String> command, ProcessOutputReader outputReader) throws IOException, InterruptedException {
        if (isCancelled()) {
            return false;
        }

        ProcessBuilder builder = new ProcessBuilder().command(command).redirectErrorStream(true);
        Process process = builder.start();
        outputReader.setInputStream(process.getInputStream());
        Thread readerThread = new Thread(outputReader);
        readerThread.run();
        Archivo.logger.info("Checking if process is alive...");
//        while (process.isAlive()) {
        while (!process.waitFor(0, TimeUnit.MILLISECONDS)) {
            Archivo.logger.info("Process is alive!");
            if (isCancelled()) {
                Archivo.logger.info("Process cancelled, waiting for it to exit");
                process.destroy();
                process.waitFor();
                readerThread.interrupt();
                readerThread.join();
                Archivo.logger.info("Process has exited");
                return false;
            } else {
                Thread.sleep(100);
            }
        }
        Archivo.logger.info("process is no longer alive");
        if (process.exitValue() == 0) {
            return true;
        } else {
            Archivo.logger.error("Error running command {}: exit code = {}", command, process.exitValue());
            return false;
        }
    }

    private void cleanupFiles(Path... files) {
        cleanupFiles(Arrays.asList(files));
    }

    private void cleanupFiles(List<Path> files) {
        files.stream().forEach(f -> {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                Archivo.logger.error("Error removing {}: ", f, e);
            }
        });
    }

    private Path buildPath(Path input, String newSuffix) {
        String filename = input.getFileName().toString();
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            filename = filename.substring(0, lastDot + 1);
        }
        return Paths.get(input.getParent().toString(), filename + newSuffix);
    }
}
