package com.subtitlefind.service;

import com.subtitlefind.model.SubtitleInfo;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字幕网站爬取服务
 * 从 subtitlecat.com 网站爬取字幕信息
 */
public class SubtitleCrawlerService {

    private static final Logger logger = LoggerFactory.getLogger(SubtitleCrawlerService.class);
    private static final String BASE_URL = "https://subtitlecat.com";
    private static final String SEARCH_URL = BASE_URL + "/index.php";

    /** 代理主机 */
    private static final String PROXY_HOST = "127.0.0.1";
    /** 代理端口 */
    private static final int PROXY_PORT = 7890;

    private final OkHttpClient httpClient;
    private final Pattern downloadPattern = Pattern.compile("(\\d+)");
    private final Pattern commentPattern = Pattern.compile("(\\d+)");

    public SubtitleCrawlerService() {
        this.httpClient = new OkHttpClient.Builder()
            .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT)))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(new UserAgentInterceptor())
            .build();
    }

    /**
     * 根据关键词搜索字幕
     *
     * @param keyword 搜索关键词（视频文件名）
     * @return 字幕信息列表
     */
    public List<SubtitleInfo> searchSubtitles(String keyword) {
        List<SubtitleInfo> subtitles = new ArrayList<>();

        try {
            //logger.info("开始搜索字幕，关键词: {}", keyword);
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String searchUrl = SEARCH_URL + "?search=" + encodedKeyword;

            Request request = new Request.Builder()
                .url(searchUrl)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("搜索请求失败，状态码: {}", response.code());
                    return subtitles;
                }

                // 尝试获取正确的字符编码
                String responseBody = getResponseBodyWithCorrectEncoding(response);
                logger.debug("响应体前100个字符: {}", responseBody.length() > 100 ? responseBody.substring(0, 100) : responseBody);
                subtitles = parseSearchResults(responseBody, keyword);

            }
        } catch (IOException e) {
            logger.error("搜索字幕时发生网络错误: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("搜索字幕时发生未知错误: {}", e.getMessage(), e);
        }

        logger.info("搜索完成，找到 {} 个相关字幕", subtitles.size());
        return subtitles;
    }

    /**
     * 解析搜索结果页面
     *
     * @param html HTML内容
     * @param keyword 搜索关键词
     * @return 字幕信息列表
     */
    private List<SubtitleInfo> parseSearchResults(String html, String keyword) {
        List<SubtitleInfo> subtitles = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);

            // 尝试多种可能的选择器来查找表格行
            Elements rows = null;
            String[] selectors = {
                ".sub-table tbody tr",
                "table tbody tr",
                ".results tbody tr",
                ".search-results tbody tr",
                "tbody tr",
                "tr"
            };

            for (String selector : selectors) {
                rows = doc.select(selector);
                if (!rows.isEmpty()) {
                    logger.debug("使用选择器 '{}' 找到 {} 行数据", selector, rows.size());
                    break;
                }
            }

            if (rows == null || rows.isEmpty()) {
                logger.warn("未找到任何表格行数据");
                logger.debug("HTML响应前500字符: {}", html.length() > 500 ? html.substring(0, 500) : html);
                return subtitles;
            }

            // 只取前5个结果
            for (int i = 0; i < Math.min(rows.size(), 5); i++) {
                Element row = rows.get(i);
                SubtitleInfo subtitleInfo = parseSubtitleRow(row);

                if (subtitleInfo != null) {
                    // 检查标题是否匹配关键词
                    if (isMatch(subtitleInfo.getTitle(), keyword)) {
                        logger.debug("找到匹配的字幕: {}", subtitleInfo.getTitle());
                        subtitles.add(subtitleInfo);
                    } else {
                        logger.debug("字幕标题不匹配: {}", subtitleInfo.getTitle());
                    }
                }
            }

            // 按下载量和评论数排序
            subtitles.sort((a, b) -> {
                if (b.getComments() != a.getComments()) {
                    return Integer.compare(b.getComments(), a.getComments());
                }
                return Integer.compare(b.getDownloads(), a.getDownloads());
            });

        } catch (Exception e) {
            logger.error("解析搜索结果时发生错误: {}", e.getMessage(), e);
        }

        return subtitles;
    }

    /**
     * 解析单行字幕信息
     *
     * @param row 表格行元素
     * @return 字幕信息
     */
    private SubtitleInfo parseSubtitleRow(Element row) {
        try {
            Elements tds = row.select("td");
            if (tds.size() < 4) {
                return null;
            }

            // 获取标题和链接
            Element titleCell = tds.get(0);
            Element titleLink = titleCell.select("a").first();
            if (titleLink == null) {
                return null;
            }

            String title = titleLink.text().trim();
            String href = titleLink.attr("href");

            // 获取下载次数
            Element downloadCell = tds.get(1);
            int downloads = parseNumber(downloadCell.text());

            // 获取评论数
            Element commentCell = tds.get(2);
            int comments = parseNumber(commentCell.text());

            return new SubtitleInfo(title, href, null, downloads, comments, "zh-CN");

        } catch (Exception e) {
            logger.error("解析字幕行信息时发生错误: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取字幕下载链接
     *
     * @param subtitleInfo 字幕信息
     * @return 更新了下载链接的字幕信息
     */
    public SubtitleInfo getSubtitleDownloadUrl(SubtitleInfo subtitleInfo) {
        try {
            String detailUrl = BASE_URL + "/" + subtitleInfo.getHref();
            logger.debug("获取字幕详情页: {}", detailUrl);

            Request request = new Request.Builder()
                .url(detailUrl)
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("获取字幕详情页失败，状态码: {}", response.code());
                    return subtitleInfo;
                }

                String responseBody = getResponseBodyWithCorrectEncoding(response);
                String downloadUrl = parseDownloadUrl(responseBody, subtitleInfo.getLanguage());

                if (downloadUrl != null) {
                    subtitleInfo.setDownloadUrl(BASE_URL + downloadUrl);
                    logger.debug("成功获取下载链接: {}", subtitleInfo.getDownloadUrl());
                } else {
                    logger.warn("未找到中文字幕下载链接");
                }
            }
        } catch (Exception e) {
            logger.error("获取字幕下载链接时发生错误: {}", e.getMessage(), e);
        }

        return subtitleInfo;
    }

    /**
     * 解析下载链接
     *
     * @param html HTML内容
     * @param language 语言代码
     * @return 下载链接
     */
    private String parseDownloadUrl(String html, String language) {
        try {
            Document doc = Jsoup.parse(html);

            // 查找中文字幕下载链接
            Element downloadElement = doc.select("#download_zh-CN, #download_zh, .download-link").first();

            if (downloadElement != null) {
                String href = downloadElement.attr("href");
                if (href != null && !href.isEmpty()) {
                    return href;
                }
            }

            // 如果没找到特定的中文链接，尝试找第一个下载链接
            Elements downloadLinks = doc.select("a[href*='download'], .download");
            for (Element link : downloadLinks) {
                String href = link.attr("href");
                if (href.contains("download") || href.contains(".srt") || href.contains(".ass")) {
                    return href;
                }
            }

        } catch (Exception e) {
            logger.error("解析下载链接时发生错误: {}", e.getMessage(), e);
        }

        return null;
    }

    /**
     * 检查字幕标题是否匹配关键词
     *
     * @param title 字幕标题
     * @param keyword 关键词
     * @return 是否匹配
     */
    private boolean isMatch(String title, String keyword) {
        if (title == null || keyword == null) {
            return false;
        }

        String lowerTitle = title.toLowerCase();
        String lowerKeyword = keyword.toLowerCase();

        // 清理字幕标题中的质量后缀，用于更准确的匹配
        String cleanedTitle = VideoFileScanner.getCleanedFileNameForSearch(lowerTitle);

        // 多重匹配策略：
        // 1. 简单包含匹配
        if (lowerTitle.contains(lowerKeyword) || lowerKeyword.contains(lowerTitle)) {
            return true;
        }

        // 2. 清理后的标题匹配
        if (cleanedTitle.contains(lowerKeyword) || lowerKeyword.contains(cleanedTitle)) {
            return true;
        }

        // 3. 清理后的关键词与原标题匹配
        String cleanedKeyword = VideoFileScanner.getCleanedFileNameForSearch(lowerKeyword);
        if (lowerTitle.contains(cleanedKeyword) || cleanedKeyword.contains(lowerTitle)) {
            return true;
        }

        return false;
    }

    /**
     * 解析数字字符串
     *
     * @param text 文本
     * @return 数字
     */
    private int parseNumber(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }

        Matcher matcher = downloadPattern.matcher(text.trim());
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                logger.debug("解析数字失败: {}", text);
            }
        }

        return 0;
    }

    /**
     * 获取正确编码的响应体
     *
     * @param response HTTP响应
     * @return 正确编码的响应体字符串
     */
    private String getResponseBodyWithCorrectEncoding(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }

        // 尝试从Content-Type头获取编码
        String contentType = response.header("Content-Type");
        Charset charset = StandardCharsets.UTF_8; // 默认UTF-8

        if (contentType != null) {
            // 检查是否指定了字符集
            if (contentType.toLowerCase().contains("charset=")) {
                String[] parts = contentType.toLowerCase().split("charset=");
                if (parts.length > 1) {
                    String charsetName = parts[1].split(";")[0].trim();
                    try {
                        charset = Charset.forName(charsetName);
                        logger.debug("检测到字符编码: {}", charsetName);
                    } catch (Exception e) {
                        logger.debug("不支持的字符编码: {}, 使用UTF-8", charsetName);
                    }
                }
            }
        }

        byte[] bytes = body.bytes();
        String content = new String(bytes, charset);

        // 如果是UTF-8但包含乱码，尝试其他编码
        if (charset == StandardCharsets.UTF_8 && containsGarbledText(content)) {
            logger.debug("UTF-8解码可能有问题，尝试其他编码");

            // 尝试GB2312/GBK
            try {
                String gbkContent = new String(bytes, Charset.forName("GBK"));
                if (!containsGarbledText(gbkContent)) {
                    logger.debug("使用GBK编码成功");
                    return gbkContent;
                }
            } catch (Exception e) {
                logger.debug("GBK编码失败");
            }

            // 尝试ISO-8859-1
            try {
                String isoContent = new String(bytes, StandardCharsets.ISO_8859_1);
                if (!containsGarbledText(isoContent)) {
                    logger.debug("使用ISO-8859-1编码成功");
                    return isoContent;
                }
            } catch (Exception e) {
                logger.debug("ISO-8859-1编码失败");
            }
        }

        return content;
    }

    /**
     * 检查文本是否包含乱码
     */
    private boolean containsGarbledText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // 检查是否包含大量问号或其他乱码字符
        long questionMarks = text.chars().filter(ch -> ch == '?' || ch == 65533).count();
        return questionMarks > text.length() * 0.1; // 如果超过10%是问号，认为是乱码
    }

    /**
     * User-Agent 拦截器
     */
    private static class UserAgentInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request originalRequest = chain.request();
            Request requestWithUserAgent = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.8,en-US;q=0.5,en;q=0.3")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .build();
            return chain.proceed(requestWithUserAgent);
        }
    }

    /**
     * 关闭HTTP客户端
     */
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}