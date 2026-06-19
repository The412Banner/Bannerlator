package com.winlator.star.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

public class Downloader {

    /** Reports download progress. fraction is 0..1, or -1 when total size is unknown. */
    public interface ProgressListener {
        void onProgress(float fraction);
    }

    public static boolean downloadFile(String address, File file) {
        return downloadFile(address, file, null);
    }

    public static boolean downloadFile(String address, File file, ProgressListener listener) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            InputStream input = connection.getInputStream();

            long total = connection.getContentLengthLong();

            OutputStream output = new FileOutputStream(file.getAbsolutePath());

            byte[] data = new byte[8192];

            long readTotal = 0;
            float lastReported = -2f;
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
                readTotal += count;
                if (listener != null) {
                    float fraction = total > 0 ? (float) readTotal / (float) total : -1f;
                    // Throttle to whole-percent steps to avoid flooding the UI thread.
                    if (fraction < 0f || fraction - lastReported >= 0.01f || fraction >= 1f) {
                        lastReported = fraction;
                        listener.onProgress(fraction);
                    }
                }
            }

            output.flush();
            output.close();
            input.close();
            if (listener != null) listener.onProgress(1f);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String downloadString(String address) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
