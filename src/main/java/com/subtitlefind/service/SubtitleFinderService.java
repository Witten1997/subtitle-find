package com.subtitlefind.service;

import com.subtitlefind.model.SubtitleInfo;
import com.subtitlefind.web.service.LogWebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 字幕查找主服务 - Web增强版
 * 整合所有子服务，提供完整的字幕查找功能，支持Web界面回调
 */
@Service
public class SubtitleFinderService {

    private static final Logger logger = LoggerFactory.getLogger(SubtitleFinderService.class);

    private final VideoFileScanner videoFileScanner;
    private final SubtitleCrawlerService subtitleCrawler;
    private final SubtitleDownloadService subtitleDownloader;
    private final ExecutorService executorService;

    @Autowired(required = false)
    @Lazy
    private LogWebSocketService logWebSocketService;

    private BiConsumer<String, String> logCallback;

    public SubtitleFinderService() {
        this.videoFileScanner = new VideoFileScanner();
        this.subtitleCrawler = new SubtitleCrawlerService();
        this.subtitleDownloader = new SubtitleDownloadService();
        this.executorService = Executors.newFixedThreadPool(5);
    }

    /**
     * 设置日志回调函数
     */
    public void setLogCallback(BiConsumer<String, String> callback) {
        this.logCallback = callback;
    }

    /**
     * 记录日志
     */
    private void log(String level, String message) {
        if (logCallback != null) {
            logCallback.accept(level, message);
        }

        switch (level.toUpperCase()) {
            case "INFO":
                logger.info(message);
                break;
            case "WARN":
                logger.warn(message);
                break;
            case "ERROR":
                logger.error(message);
                break;
            case "DEBUG":
                logger.debug(message);
                break;
            default:
                logger.info(message);
                break;
        }
    }

    /**
     * 为指定目录下的所有视频文件查找并下载字幕
     *
     * @param directoryPath 视频文件目录路径
     */
    public void findSubtitlesForDirectory(String directoryPath) {
        log("INFO", "开始为目录中的视频文件查找字幕: " + directoryPath);

        try {
            // 1. 扫描视频文件
            log("INFO", "正在扫描视频文件...");
            List<VideoFileScanner.VideoFileInfo> videoFiles = videoFileScanner.scanVideoFiles(directoryPath);

            if (videoFiles.isEmpty()) {
                log("WARN", "未找到任何视频文件");
                return;
            }

            log("INFO", "找到 " + videoFiles.size() + " 个视频文件，开始处理");

            int processedCount = 0;
            int successCount = 0;

            // 2. 为每个视频文件查找字幕
            for (VideoFileScanner.VideoFileInfo videoFile : videoFiles) {
                processedCount++;
                log("INFO", String.format("处理视频文件 (%d/%d): %s",
                    processedCount, videoFiles.size(), videoFile.getFileName()));

                try {
                    boolean success = processVideoFile(videoFile);
                    if (success) {
                        successCount++;
                        log("INFO", "成功为视频文件下载字幕: " + videoFile.getFileNameWithoutExtension());
                    } else {
                        log("WARN", "未能为视频文件找到字幕: " + videoFile.getFileNameWithoutExtension());
                    }
                } catch (Exception e) {
                    log("ERROR", "处理视频文件时发生错误: " + videoFile.getFileName() + " - " + e.getMessage());
                }

                // 添加延迟以避免过于频繁的请求
                if (processedCount < videoFiles.size()) {
                    try {
                        Thread.sleep(500); // 延迟2秒
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log("INFO", String.format("处理完成！总视频文件: %d, 成功下载字幕: %d",
                videoFiles.size(), successCount));

        } catch (Exception e) {
            log("ERROR", "查找字幕过程中发生未知错误: " + e.getMessage());
        }
    }

    /**
     * 处理单个视频文件
     *
     * @param videoFile 视频文件信息
     * @return 是否成功下载字幕
     */
    private boolean processVideoFile(VideoFileScanner.VideoFileInfo videoFile) {
        try {
            String videoFileName = videoFile.getFileNameWithoutExtension();
            String directory = videoFile.getDirectory();

            // 检查是否已存在字幕文件
            if (subtitleDownloader.subtitleExists(videoFileName, directory)) {
                log("INFO", "视频文件已有字幕，跳过: " + videoFileName);
                return true;
            }

            // 清理文件名用于搜索（去除4K、BD等后缀）
            String cleanedFileName = VideoFileScanner.getCleanedFileNameForSearch(videoFileName);

            if (!cleanedFileName.equals(videoFileName)) {
                log("INFO", "清理文件名用于搜索: " + videoFileName + " -> " + cleanedFileName);
            }

            log("INFO", "开始为视频文件查找字幕: " + videoFileName);

            // 1. 使用清理后的文件名搜索字幕
            List<SubtitleInfo> subtitles = subtitleCrawler.searchSubtitles(cleanedFileName);

            if (subtitles.isEmpty()) {
                log("WARN", "未找到匹配的字幕: " + cleanedFileName);
                return false;
            }

            //log("INFO", "找到 " + subtitles.size() + " 个候选字幕");

            // 2. 获取第一个字幕的下载链接并下载
            for (SubtitleInfo subtitle : subtitles) {
                try {
                    log("INFO", "尝试下载字幕: " + subtitle.getTitle());

                    // 获取下载链接
                    SubtitleInfo subtitleWithUrl = subtitleCrawler.getSubtitleDownloadUrl(subtitle);

                    if (subtitleWithUrl.getDownloadUrl() != null) {
                        // 下载字幕文件 - 使用原始文件名（保留4K、BD等后缀）
                        boolean downloadSuccess = subtitleDownloader.downloadSubtitle(
                            subtitleWithUrl, videoFileName, directory);

                        if (downloadSuccess) {
                            log("INFO", "成功下载字幕: " + subtitle.getTitle() + " -> " + videoFileName);
                            return true;
                        } else {
                            log("WARN", "下载失败，尝试下一个字幕: " + subtitle.getTitle());
                        }
                    } else {
                        log("WARN", "未找到下载链接，尝试下一个字幕: " + subtitle.getTitle());
                    }

                    // 添加延迟避免请求过于频繁
                    Thread.sleep(1000);

                } catch (Exception e) {
                    log("ERROR", "处理字幕时发生错误: " + subtitle.getTitle() + " - " + e.getMessage());
                }
            }

            log("WARN", "所有字幕尝试均失败: " + videoFileName);
            return false;

        } catch (Exception e) {
            log("ERROR", "处理视频文件时发生错误: " + videoFile.getFileName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 为单个视频文件查找字幕
     *
     * @param videoFilePath 视频文件完整路径
     * @return 是否成功下载字幕
     */
    public boolean findSubtitleForSingleFile(String videoFilePath) {
        try {
            log("INFO", "为单个视频文件查找字幕: " + videoFilePath);

            java.nio.file.Path path = java.nio.file.Paths.get(videoFilePath);
            if (!java.nio.file.Files.exists(path) || !java.nio.file.Files.isRegularFile(path)) {
                log("ERROR", "视频文件不存在或不是文件: " + videoFilePath);
                return false;
            }

            String fileName = path.getFileName().toString();
            String directory = path.getParent().toString();
            String fileNameWithoutExtension = getFileNameWithoutExtension(fileName);

            VideoFileScanner.VideoFileInfo videoFile = new VideoFileScanner.VideoFileInfo(
                videoFilePath, fileName, fileNameWithoutExtension, directory, 0);

            return processVideoFile(videoFile);

        } catch (Exception e) {
            log("ERROR", "为单个文件查找字幕时发生错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取不带扩展名的文件名
     */
    private String getFileNameWithoutExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(0, lastDotIndex);
        }
        return fileName;
    }

    /**
     * 关闭服务并释放资源
     */
    public void shutdown() {
        log("INFO", "正在关闭字幕查找服务...");

        try {
            // 关闭线程池
            executorService.shutdown();
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }

            // 关闭HTTP客户端
            subtitleCrawler.close();
            subtitleDownloader.close();

            log("INFO", "字幕查找服务已关闭");

        } catch (Exception e) {
            log("ERROR", "关闭服务时发生错误: " + e.getMessage());
        }
    }
}