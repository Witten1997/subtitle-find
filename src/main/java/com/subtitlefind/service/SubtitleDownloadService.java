package com.subtitlefind.service;

import com.subtitlefind.model.SubtitleInfo;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 字幕文件下载服务
 * 负责下载字幕文件并重命名保存
 */
public class SubtitleDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(SubtitleDownloadService.class);

    /** 代理主机 */
    private static final String PROXY_HOST = "127.0.0.1";
    /** 代理端口 */
    private static final int PROXY_PORT = 7890;

    private final OkHttpClient httpClient;

    public SubtitleDownloadService() {
        this.httpClient = new OkHttpClient.Builder()
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT)))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request originalRequest = chain.request();
                    Request requestWithUserAgent = originalRequest.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                            .header("Accept", "*/*")
                            .header("Accept-Language", "zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3")
                            .header("Accept-Encoding", "gzip, deflate")
                            .header("Connection", "keep-alive")
                            .build();
                    return chain.proceed(requestWithUserAgent);
                })
                .build();
    }

    /**
     * 下载字幕文件并保存到指定目录
     *
     * @param subtitleInfo    字幕信息
     * @param videoFileName   视频文件名（不含扩展名）
     * @param targetDirectory 目标目录
     * @return 是否下载成功
     */
    public boolean downloadSubtitle(SubtitleInfo subtitleInfo, String videoFileName, String targetDirectory) {
        if (subtitleInfo.getDownloadUrl() == null || subtitleInfo.getDownloadUrl().isEmpty()) {
            logger.error("字幕下载链接为空: {}", subtitleInfo.getTitle());
            return false;
        }


        try {
            logger.info("开始下载字幕: {} -> {}", subtitleInfo.getTitle(), videoFileName);

            // 创建目标目录
            Path targetDir = Paths.get(targetDirectory);
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            // 下载字幕文件
            byte[] subtitleContent = downloadSubtitleContent(subtitleInfo.getDownloadUrl());
            if (subtitleContent == null || subtitleContent.length == 0) {
                logger.error("下载的字幕内容为空");
                return false;
            }

            // 验证字幕文件内容
            if (!isValidSubtitleContent(subtitleContent)) {
                logger.error("下载的内容不是有效的字幕文件，可能是404页面或其他错误内容");
                return false;
            }

            // 确定字幕文件扩展名
            String subtitleExtension = getSubtitleExtension(subtitleInfo.getDownloadUrl(), subtitleContent);

            // 生成目标文件名
            String targetFileName = videoFileName + "." + subtitleExtension;
            Path targetFilePath = targetDir.resolve(targetFileName);

            // 检查是否已存在同名字幕文件
            if (Files.exists(targetFilePath)) {
                logger.info("字幕文件已存在，跳过下载: {}", targetFilePath);
                return true;
            }

            // 保存字幕文件
            try (FileOutputStream fos = new FileOutputStream(targetFilePath.toFile())) {
                fos.write(subtitleContent);
                fos.flush();
            }

            logger.info("字幕下载成功: {}", targetFilePath);
            return true;

        } catch (Exception e) {
            logger.error("下载字幕文件时发生错误: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 下载字幕文件内容
     *
     * @param downloadUrl 下载链接
     * @return 字幕文件内容
     */
    private byte[] downloadSubtitleContent(String downloadUrl) {
        try {
            logger.debug("发起下载请求: {}", downloadUrl);

            Request request = new Request.Builder()
                    .url(downloadUrl)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("下载请求失败，状态码: {}, URL: {}", response.code(), downloadUrl);
                    return null;
                }

                byte[] content = response.body().bytes();
                logger.debug("下载完成，文件大小: {} 字节", content.length);

                return content;
            }
        } catch (Exception e) {
            logger.error("下载字幕内容时发生错误: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据下载链接和文件内容确定字幕文件扩展名
     *
     * @param downloadUrl 下载链接
     * @param content     文件内容
     * @return 文件扩展名
     */
    private String getSubtitleExtension(String downloadUrl, byte[] content) {
        // 首先尝试从URL中获取扩展名
        String url = downloadUrl.toLowerCase();
        if (url.contains(".srt")) {
            return "srt";
        } else if (url.contains(".ass")) {
            return "ass";
        } else if (url.contains(".ssa")) {
            return "ssa";
        } else if (url.contains(".vtt")) {
            return "vtt";
        }

        // 如果URL中没有明确的扩展名，尝试从文件内容判断
        try {
            String contentString = new String(content, "UTF-8").trim();

            // 检查是否是SRT格式（包含时间码格式 00:00:00,000）
            if (contentString.contains("-->") && contentString.matches(".*\\d{2}:\\d{2}:\\d{2},\\d{3}.*")) {
                return "srt";
            }

            // 检查是否是ASS格式
            if (contentString.contains("[Script Info]") || contentString.contains("[V4+ Styles]")) {
                return "ass";
            }

            // 检查是否是VTT格式
            if (contentString.startsWith("WEBVTT")) {
                return "vtt";
            }

        } catch (Exception e) {
            logger.debug("分析文件内容时出错: {}", e.getMessage());
        }

        // 默认返回srt
        return "srt";
    }

    /**
     * 批量下载字幕文件
     *
     * @param videoFileName   视频文件名（不含扩展名）
     * @param targetDirectory 目标目录
     * @param subtitles       字幕信息列表
     * @return 成功下载的字幕数量
     */
    public int downloadSubtitles(String videoFileName, String targetDirectory, java.util.List<SubtitleInfo> subtitles) {
        int successCount = 0;

        for (SubtitleInfo subtitle : subtitles) {
            try {
                if (downloadSubtitle(subtitle, videoFileName, targetDirectory)) {
                    successCount++;
                    logger.info("已成功下载字幕 ({}/{}): {}", successCount, subtitles.size(), subtitle.getTitle());
                    // 只下载第一个成功的字幕文件
                    break;
                } else {
                    logger.warn("字幕下载失败: {}", subtitle.getTitle());
                }
            } catch (Exception e) {
                logger.error("处理字幕时发生错误: {}", e.getMessage(), e);
            }
        }

        return successCount;
    }

    /**
     * 检查字幕文件是否已存在
     *
     * @param videoFileName 视频文件名（不含扩展名）
     * @param directory     目录路径
     * @return 是否已存在字幕文件
     */
    public boolean subtitleExists(String videoFileName, String directory) {
        Path dirPath = Paths.get(directory);
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            return false;
        }

        String[] subtitleExtensions = {"srt", "ass", "ssa", "vtt"};

        for (String ext : subtitleExtensions) {
            Path subtitlePath = dirPath.resolve(videoFileName + "." + ext);
            if (Files.exists(subtitlePath)) {
                // 检查现有字幕文件是否有效
                if (isExistingSubtitleValid(subtitlePath)) {
                    logger.debug("发现已存在的有效字幕文件: {}", subtitlePath);
                    return true;
                } else {
                    // 删除无效的字幕文件
                    logger.warn("发现无效字幕文件，将删除: {}", subtitlePath);
                    try {
                        Files.deleteIfExists(subtitlePath);
                        logger.info("已删除无效字幕文件: {}", subtitlePath);
                    } catch (Exception e) {
                        logger.error("删除无效字幕文件失败: {}", e.getMessage(), e);
                    }
                }
            }
        }

        return false;
    }

    /**
     * 检查现有字幕文件是否有效
     *
     * @param subtitlePath 字幕文件路径
     * @return 是否有效
     */
    private boolean isExistingSubtitleValid(Path subtitlePath) {
        try {
            byte[] content = Files.readAllBytes(subtitlePath);
            return isValidSubtitleContent(content);
        } catch (Exception e) {
            logger.debug("读取现有字幕文件失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 验证字幕文件内容是否有效
     *
     * @param content 文件内容
     * @return 是否是有效的字幕文件
     */
    private boolean isValidSubtitleContent(byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }

        try {
            String contentString = new String(content, "UTF-8").trim().toLowerCase();

            // 检查文件大小，太小的文件可能不是有效字幕
            if (content.length < 50) {
                logger.debug("文件太小，可能不是有效字幕: {} 字节", content.length);
                return false;
            }

            // 检查是否是HTML错误页面
            if (contentString.contains("<html>") &&
                    contentString.contains("<head>") &&
                    contentString.contains("<body>")) {
                logger.warn("检测到HTML错误页面内容，不是有效字幕文件");
                return false;
            }

            // 检查是否包含常见的错误响应
            if (contentString.contains("error") &&
                    (contentString.contains("server") || contentString.contains("服务器"))) {
                logger.warn("检测到错误响应内容");
                return false;
            }

            // 检查是否包含字幕的典型特征
            boolean hasTimeCode = contentString.contains("-->") ||
                    contentString.matches(".*\\d{2}:\\d{2}:\\d{2}[,.]\\d{3}.*");

            boolean hasSubtitleFormat = contentString.contains("[script info]") ||
                    contentString.contains("[v4+ styles]") ||
                    contentString.startsWith("webvtt") ||
                    contentString.matches(".*\\d+\\s*\\n.*-->.*");

            if (hasTimeCode || hasSubtitleFormat) {
                logger.debug("检测到有效的字幕文件特征");
                return true;
            }

            // 对于纯文本内容，检查是否包含合理的字幕行数
            String[] lines = contentString.split("\n");
            if (lines.length >= 3) {
                // 简单检查：如果有多行内容且不是HTML，可能是字幕
                boolean looksLikeSubtitle = false;
                for (String line : lines) {
                    if (line.trim().matches("\\d+") || // 序号行
                            line.contains("-->") ||         // 时间行
                            line.trim().length() > 5) {     // 内容行
                        looksLikeSubtitle = true;
                        break;
                    }
                }
                if (looksLikeSubtitle) {
                    logger.debug("内容看起来像字幕文件");
                    return true;
                }
            }

            logger.warn("文件内容不符合字幕格式特征");
            return false;

        } catch (Exception e) {
            logger.error("验证字幕内容时发生错误: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 关闭下载服务
     */
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}