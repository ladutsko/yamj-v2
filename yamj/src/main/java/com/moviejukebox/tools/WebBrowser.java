/*
 *      Copyright (c) 2004-2012 YAMJ Members
 *      http://code.google.com/p/moviejukebox/people/list
 *
 *      Web: http://code.google.com/p/moviejukebox/
 *
 *      This software is licensed under a Creative Commons License
 *      See this page: http://code.google.com/p/moviejukebox/wiki/License
 *
 *      For any reuse or distribution, you must make clear to others the
 *      license terms of this work.
 */
package com.moviejukebox.tools;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;

/**
 * Web browser with simple cookies support
 */
public class WebBrowser {

    private static final Logger logger = Logger.getLogger(WebBrowser.class);

    private Map<String, String> browserProperties;
    private Map<String, Map<String, String>> cookies;
    private static String mjbProxyHost;
    private static String mjbProxyPort;
    private static String mjbProxyUsername;
    private static String mjbProxyPassword;
    private static String mjbEncodedPassword;
    private static int mjbTimeoutConnect = 25000;
    private static int mjbTimeoutRead = 90000;

    private int imageRetryCount;

    public WebBrowser() {
        browserProperties = new HashMap<String, String>();
        browserProperties.put("User-Agent", "Mozilla/5.25 Netscape/5.0 (Windows; I; Win95)");
        String browserLanguage = PropertiesUtil.getProperty("mjb.Accept-Language", null);
        if (browserLanguage != null && browserLanguage.trim().length()>0){
            browserProperties.put("Accept-Language", browserLanguage.trim());
        }

        cookies = new HashMap<String, Map<String, String>>();

        mjbProxyHost = PropertiesUtil.getProperty("mjb.ProxyHost", null);
        mjbProxyPort = PropertiesUtil.getProperty("mjb.ProxyPort", null);
        mjbProxyUsername = PropertiesUtil.getProperty("mjb.ProxyUsername", null);
        mjbProxyPassword = PropertiesUtil.getProperty("mjb.ProxyPassword", null);

        try {
            mjbTimeoutConnect = PropertiesUtil.getIntProperty("mjb.Timeout.Connect", "25000");
        } catch (Exception ignore) {
            // If the conversion fails use the default value
            mjbTimeoutConnect = 25000;
        }

        try {
            mjbTimeoutRead = PropertiesUtil.getIntProperty("mjb.Timeout.Read", "90000");
        } catch (Exception ignore) {
            // If the conversion fails use the default value
            mjbTimeoutRead = 90000;
        }

        imageRetryCount = PropertiesUtil.getIntProperty("mjb.imageRetryCount", "3");
        if (imageRetryCount < 1) {
            imageRetryCount = 1;
        }

        if (mjbProxyUsername != null) {
            mjbEncodedPassword = mjbProxyUsername + ":" + mjbProxyPassword;
            mjbEncodedPassword = "Basic " + new String(Base64.encodeBase64((mjbProxyUsername + ":" + mjbProxyPassword).getBytes()));
        }

        if (logger.isTraceEnabled()) {
            showStatus();
        }
    }

    public String request(String url) throws IOException {
        return request(new URL(url));
    }

    public String request(String url, Charset charset) throws IOException {
        return request(new URL(url), charset);
    }

    public URLConnection openProxiedConnection(URL url) throws IOException {
        if (mjbProxyHost != null) {
            System.getProperties().put("proxySet", "true");
            System.getProperties().put("proxyHost", mjbProxyHost);
            System.getProperties().put("proxyPort", mjbProxyPort);
        }

        URLConnection cnx = url.openConnection();

        if (mjbProxyUsername != null) {
            cnx.setRequestProperty("Proxy-Authorization", mjbEncodedPassword);
        }

        cnx.setConnectTimeout(mjbTimeoutConnect);
        cnx.setReadTimeout(mjbTimeoutRead);

        return cnx;
    }

    public String request(URL url) throws IOException {
        return request(url, null);
    }

    public String request(URL url, Charset charset) throws IOException {
        logger.debug("WebBrowser: Requesting " + url.toString());
        StringWriter content = null;

        // get the download limit for the host
        ThreadExecutor.enterIO(url);
        content = new StringWriter(10*1024);
        try {

            URLConnection cnx = null;

            try {
                cnx = openProxiedConnection(url);

                sendHeader(cnx);
                readHeader(cnx);

                if (charset == null) {
                    charset = getCharset(cnx);
                }

                BufferedReader in = null;
                try {

                    // If we fail to get the URL information we need to exit gracefully
                    in = new BufferedReader(new InputStreamReader(cnx.getInputStream(), charset));

                    String line;
                    while ((line = in.readLine()) != null) {
                        content.write(line);
                    }
                    // Attempt to force close connection
                    // We have HTTP connections, so these are always valid
                    content.flush();
                } catch (Exception error) {
                    logger.error("WebBrowser: Error getting URL " + url.toString());
                }
                finally {
                    if (in != null) {
                        in.close();
                    }
                }
            } catch (SocketTimeoutException error) {
                logger.error("Timeout Error with " + url.toString());
            } finally {
                if (cnx != null) {
                    if(cnx instanceof HttpURLConnection) {
                        ((HttpURLConnection)cnx).disconnect();
                    }
                }
            }
            return content.toString();
        } finally {
            content.close();
            ThreadExecutor.leaveIO();
        }
    }

    /**
     * Download the image for the specified URL into the specified file.
     *
     * @throws IOException
     */
    public void downloadImage(File imageFile, String imageURL) throws IOException {

        String fixedImageURL = new String(imageURL);
        if (fixedImageURL.contains(" ")) {
            fixedImageURL.replaceAll(" ", "%20");
        }

        URL url = new URL(fixedImageURL);

        ThreadExecutor.enterIO(url);
        boolean success = false;
        int retryCount = imageRetryCount;
        try {
            while (!success && retryCount > 0) {
                URLConnection cnx = openProxiedConnection(url);

                sendHeader(cnx);
                readHeader(cnx);

                int reportedLength = cnx.getContentLength();
                java.io.InputStream inputStream = cnx.getInputStream();
                int inputStreamLength = FileTools.copy(inputStream, new FileOutputStream(imageFile));

                if (reportedLength < 0 || reportedLength == inputStreamLength) {
                    success = true;
                } else {
                    retryCount--;
                    logger.debug("WebBrowser: Image download attempt failed, bytes expected: " + reportedLength + ", bytes received: " + inputStreamLength);
                }
            }
        } finally {
            ThreadExecutor.leaveIO();
        }

        if (!success) {
            logger.debug("WebBrowser: Failed " + imageRetryCount + " times to download image, aborting. URL: " + imageURL);
        }
    }

    /**
     * Check the URL to see if it's one of the special cases that needs to be worked around
     * @param URL   The URL to check
     * @param cnx   The connection that has been opened
     */
    private void checkRequest(URLConnection checkCnx) {
        String checkUrl = checkCnx.getURL().getHost().toLowerCase();

        // TODO: Move these workarounds into a property file so they can be overridden at runtime

        // A workaround for the need to use a referrer for thetvdb.com
        if (checkUrl.indexOf("thetvdb") > 0) {
            checkCnx.setRequestProperty("Referer", "http://forums.thetvdb.com/");
        }

        // A workaround for the kinopoisk.ru site
        if (checkUrl.indexOf("kinopoisk") > 0) {
            checkCnx.setRequestProperty("Accept", "text/html, text/plain");
            checkCnx.setRequestProperty("Accept-Language", "ru");
            checkCnx.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.9.2) Gecko/20100115 Firefox/3.6");
        }

        return;
    }

    private void sendHeader(URLConnection cnx) {
        // send browser properties
        for (Map.Entry<String, String> browserProperty : browserProperties.entrySet()) {
            cnx.setRequestProperty(browserProperty.getKey(), browserProperty.getValue());

            if (logger.isTraceEnabled()) {
                logger.trace("setRequestProperty:" + browserProperty.getKey() + "='" + browserProperty.getValue() + "'");
            }
        }

        // send cookies
        String cookieHeader = createCookieHeader(cnx);
        if (!cookieHeader.isEmpty()) {
            cnx.setRequestProperty("Cookie", cookieHeader);
            if (logger.isTraceEnabled()) {
                logger.trace("Cookie:" + cookieHeader);
            }
        }

        checkRequest(cnx);
    }

    private String createCookieHeader(URLConnection cnx) {
        String host = cnx.getURL().getHost();
        StringBuilder cookiesHeader = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> domainCookies : cookies.entrySet()) {
            if (host.endsWith(domainCookies.getKey())) {
                for (Map.Entry<String, String> cookie : domainCookies.getValue().entrySet()) {
                    cookiesHeader.append(cookie.getKey());
                    cookiesHeader.append("=");
                    cookiesHeader.append(cookie.getValue());
                    cookiesHeader.append(";");
                }
            }
        }
        if (cookiesHeader.length() > 0) {
            // remove last ; char
            cookiesHeader.deleteCharAt(cookiesHeader.length() - 1);
        }
        return cookiesHeader.toString();
    }

    private void readHeader(URLConnection cnx) {
        // read new cookies and update our cookies
        for (Map.Entry<String, List<String>> header : cnx.getHeaderFields().entrySet()) {
            if ("Set-Cookie".equals(header.getKey())) {
                for (String cookieHeader : header.getValue()) {
                    String[] cookieElements = cookieHeader.split(" *; *");
                    if (cookieElements.length >= 1) {
                        String[] firstElem = cookieElements[0].split(" *= *");
                        String cookieName = firstElem[0];
                        String cookieValue = firstElem.length > 1 ? firstElem[1] : null;
                        String cookieDomain = null;
                        // find cookie domain
                        for (int i = 1; i < cookieElements.length; i++) {
                            String[] cookieElement = cookieElements[i].split(" *= *");
                            if ("domain".equals(cookieElement[0])) {
                                cookieDomain = cookieElement.length > 1 ? cookieElement[1] : null;
                                break;
                            }
                        }
                        if (cookieDomain == null) {
                            // if domain isn't set take current host
                            cookieDomain = cnx.getURL().getHost();
                        }
                        Map<String, String> domainCookies = cookies.get(cookieDomain);
                        if (domainCookies == null) {
                            domainCookies = new HashMap<String, String>();
                            cookies.put(cookieDomain, domainCookies);
                        }
                        // add or replace cookie
                        domainCookies.put(cookieName, cookieValue);
                    }
                }
            }
        }
    }

    private Charset getCharset(URLConnection cnx) {
        Charset charset = null;
        // content type will be string like "text/html; charset=UTF-8" or "text/html"
        String contentType = cnx.getContentType();
        if (contentType != null) {
            // changed 'charset' to 'harset' in regexp because some sites send 'Charset'
            Matcher m = Pattern.compile("harset *=[ '\"]*([^ ;'\"]+)[ ;'\"]*").matcher(contentType);
            if (m.find()) {
                String encoding = m.group(1);
                try {
                    charset = Charset.forName(encoding);
                } catch (UnsupportedCharsetException error) {
                    // there will be used default charset
                }
            }
        }
        if (charset == null) {
            charset = Charset.defaultCharset();
        }

        // logger.debug("Detected charset " + charset);
        return charset;
    }

    /**
     * Get URL - allow to know if there is some redirect
     *
     * @param urlStr
     * @return
     */
    public String getUrl(String urlStr) throws Exception {
        String response = urlStr;
        URL url = new URL(urlStr);
        ThreadExecutor.enterIO(url);

        try {
            URLConnection cnx = openProxiedConnection(url);
            sendHeader(cnx);
            readHeader(cnx);
            response = cnx.getURL().toString();
        } finally {
            ThreadExecutor.leaveIO();
        }

        return response;
    }

    public static String getMjbProxyHost() {
        return mjbProxyHost;
    }

    public static String getMjbProxyPort() {
        return mjbProxyPort;
    }

    public static String getMjbProxyUsername() {
        return mjbProxyUsername;
    }

    public static String getMjbProxyPassword() {
        return mjbProxyPassword;
    }

    public static int getMjbTimeoutConnect() {
        return mjbTimeoutConnect;
    }

    public static int getMjbTimeoutRead() {
        return mjbTimeoutRead;
    }

    public static void showStatus() {
        if (mjbProxyHost != null) {
            logger.debug("WebBrowser: Proxy Host: " + mjbProxyHost);
            logger.debug("WebBrowser: Proxy Port: " + mjbProxyPort);
        } else {
            logger.debug("WebBrowser: No proxy set");
        }

        if (mjbProxyUsername != null) {
            logger.debug("WebBrowser: Proxy Host: " + mjbProxyUsername);
            if (mjbProxyPassword != null) {
                logger.debug("WebBrowser: Proxy Password: IS SET");
            }
        } else {
            logger.debug("WebBrowser: No Proxy username ");
        }

        logger.debug("WebBrowser: Connect Timeout: " + mjbTimeoutConnect);
        logger.debug("WebBrowser: Read Timeout   : " + mjbTimeoutRead);
    }
}
