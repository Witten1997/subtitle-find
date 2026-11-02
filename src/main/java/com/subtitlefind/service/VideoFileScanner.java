package com.subtitlefind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 视频文件扫描器
 * 扫描指定目录下的所有视频文件
 */
public class VideoFileScanner {

    private static final Logger logger = LoggerFactory.getLogger(VideoFileScanner.class);

    // 支持的视频文件扩展名
    private static final List<String> VIDEO_EXTENSIONS = Arrays.asList(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".3gp", ".ts"
    );

    // 视频文件最小大小：100MB（小于该大小的文件将被忽略）
    private static final long MIN_FILE_SIZE = 100L * 1024 * 1024;

    // 视频质量后缀模式（需要在搜索时去除的后缀）
    private static final Pattern QUALITY_SUFFIX_PATTERN = Pattern.compile(
        "(-(?:4k|4K|BD|bd|1080p|720p|2160p|HDR|UHD|REMUX|BluRay|WEB-DL|WEBRip|BDRip))$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * 扫描指定目录下的所有视频文件
     *
     * @param directoryPath 要扫描的目录路径
     * @return 视频文件信息列表
     */
    public List<VideoFileInfo> scanVideoFiles(String directoryPath) {
        List<VideoFileInfo> videoFiles = new ArrayList<>();
        Path rootPath = Paths.get(directoryPath);

        if (!Files.exists(rootPath)) {
            logger.error("指定的目录不存在: {}", directoryPath);
            return videoFiles;
        }

        if (!Files.isDirectory(rootPath)) {
            logger.error("指定的路径不是目录: {}", directoryPath);
            return videoFiles;
        }

        logger.info("开始扫描目录: {}", directoryPath);

        try (Stream<Path> pathStream = Files.walk(rootPath)) {
            pathStream
                .filter(Files::isRegularFile)
                .filter(this::isVideoFile)
                .filter(this::isLargeEnough)
                .forEach(path -> {
                    VideoFileInfo videoInfo = createVideoFileInfo(path);
                    if (videoInfo != null) {
                        videoFiles.add(videoInfo);
                        logger.debug("找到视频文件: {}", videoInfo.getFileName());
                    }
                });
        } catch (IOException e) {
            logger.error("扫描目录时发生错误: {}", e.getMessage(), e);
        }

        logger.info("扫描完成，共找到 {} 个视频文件", videoFiles.size());
        return videoFiles;
    }

    /**
     * 判断文件是否为视频文件
     *
     * @param path 文件路径
     * @return 是否为视频文件
     */
    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return VIDEO_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    /**
     * 判断文件大小是否达到阈值（大于等于 100MB）
     *
     * @param path 文件路径
     * @return 是否达到最小大小要求
     */
    private boolean isLargeEnough(Path path) {
        try {
            long size = Files.size(path);
            if (size < MIN_FILE_SIZE) {
                logger.debug("文件小于 {}MB，跳过: {} ({} 字节)", MIN_FILE_SIZE / 1024 / 1024, path.getFileName(), size);
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.warn("获取文件大小失败，跳过: {}", path);
            return false;
        }
    }

    /**
     * 创建视频文件信息对象
     *
     * @param path 文件路径
     * @return 视频文件信息
     */
    private VideoFileInfo createVideoFileInfo(Path path) {
        try {
            String fullPath = path.toString();
            String fileName = path.getFileName().toString();
            String fileNameWithoutExtension = getFileNameWithoutExtension(fileName);
            String directory = path.getParent().toString();
            long fileSize = Files.size(path);

            return new VideoFileInfo(fullPath, fileName, fileNameWithoutExtension, directory, fileSize);
        } catch (IOException e) {
            logger.error("获取文件信息失败: {}", path, e);
            return null;
        }
    }

    /**
     * 获取不带扩展名的文件名
     *
     * @param fileName 完整文件名
     * @return 不带扩展名的文件名
     */
    private String getFileNameWithoutExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }

    /**
     * 清理文件名，去除质量后缀，用于搜索字幕
     * 例: "OAE-276-4K" -> "OAE-276"
     *
     * @param fileName 原始文件名（不含扩展名）
     * @return 清理后的文件名
     */
    public static String getCleanedFileNameForSearch(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return fileName;
        }

        return QUALITY_SUFFIX_PATTERN.matcher(fileName.trim()).replaceAll("");
    }

    /**
     * 视频文件信息类
     */
    public static class VideoFileInfo {
        private final String fullPath;
        private final String fileName;
        private final String fileNameWithoutExtension;
        private final String directory;
        private final long fileSize;

        public VideoFileInfo(String fullPath, String fileName, String fileNameWithoutExtension,
                           String directory, long fileSize) {
            this.fullPath = fullPath;
            this.fileName = fileName;
            this.fileNameWithoutExtension = fileNameWithoutExtension;
            this.directory = directory;
            this.fileSize = fileSize;
        }

        public String getFullPath() { return fullPath; }
        public String getFileName() { return fileName; }
        public String getFileNameWithoutExtension() { return fileNameWithoutExtension; }
        public String getDirectory() { return directory; }
        public long getFileSize() { return fileSize; }

        @Override
        public String toString() {
            return "VideoFileInfo{" +
                    "fileName='" + fileName + '\'' +
                    ", directory='" + directory + '\'' +
                    ", fileSize=" + fileSize +
                    '}';
        }
    }
}