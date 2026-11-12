package com.loopers.experiment.queue;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.awt.GradientPaint;

@Component
public class WorkloadRunner {

    private static volatile int memorySink;
    private static final int IMAGE_WIDTH = 2048;
    private static final int IMAGE_HEIGHT = 2048;
    private static final int THUMBNAIL_SIZE = 512;

    private final ExperimentProperties properties;

    public WorkloadRunner(ExperimentProperties properties) {
        this.properties = properties;
    }

    public void run(WorkloadType type) throws InterruptedException, IOException {
        switch (type) {
            case CPU -> consumeCpu();
            case MEMORY -> consumeMemory();
            case DISK -> consumeDisk();
        }
    }

    private void consumeCpu() throws IOException {
        byte[] sourceJpeg = createSourceImage();
        ByteArrayOutputStream thumbnailJpeg = new ByteArrayOutputStream();
        for (int i = 0; i < properties.imageTransformsPerJob(); i++) {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(sourceJpeg));
            if (source == null) {
                throw new IOException("could not decode synthetic source image");
            }
            BufferedImage thumbnail = new BufferedImage(
                THUMBNAIL_SIZE,
                THUMBNAIL_SIZE,
                BufferedImage.TYPE_INT_RGB
            );
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, null);
            } finally {
                graphics.dispose();
            }
            thumbnailJpeg.reset();
            ImageIO.write(thumbnail, "jpg", thumbnailJpeg);
            source.flush();
            thumbnail.flush();
        }
    }

    private byte[] createSourceImage() throws IOException {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setPaint(new GradientPaint(0, 0, java.awt.Color.BLUE, IMAGE_WIDTH, IMAGE_HEIGHT,
                java.awt.Color.ORANGE));
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            graphics.setColor(java.awt.Color.WHITE);
            for (int x = 0; x < IMAGE_WIDTH; x += 64) {
                graphics.drawLine(x, 0, IMAGE_WIDTH - x, IMAGE_HEIGHT);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream sourceJpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", sourceJpeg);
        image.flush();
        return sourceJpeg.toByteArray();
    }

    private void consumeMemory() throws InterruptedException {
        byte[] allocation = new byte[properties.memoryBytesPerJob()];
        for (int index = 0; index < allocation.length; index += 4096) {
            allocation[index] = (byte) index;
        }
        Thread.sleep(properties.workDurationMs());
        memorySink = allocation[0];
    }

    private void consumeDisk() throws InterruptedException, IOException {
        Path directory = Path.of(properties.scratchDirectory());
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "experiment-", ".tmp");
        long startedAt = System.nanoTime();
        try {
            writeTemporaryFile(file);
            long remainingMs = properties.workDurationMs() - (System.nanoTime() - startedAt) / 1_000_000;
            if (remainingMs > 0) {
                Thread.sleep(remainingMs);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void writeTemporaryFile(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remainingBytes = properties.diskBytesPerJob();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            while (remainingBytes > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remainingBytes));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                remainingBytes -= buffer.limit();
            }
            channel.force(true);
        }
    }
}
