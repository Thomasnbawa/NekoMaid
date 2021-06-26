package cn.apisium.nekomaid;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import javax.activation.MimetypesFileTypeMap;

import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;

public class StaticHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
    public static final int HTTP_CACHE_SECONDS = 60;

    private FullHttpRequest request;

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        this.request = request;
        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, BAD_REQUEST);
            return;
        }

        if (!GET.equals(request.method())) return;

        final var keepAlive = HttpUtil.isKeepAlive(request);
        final var uri = request.uri();
        final var path = sanitizeUri(uri.endsWith("/") ? uri + "index.html" : uri);

        if (path == null) return;
        RandomAccessFile raf = null;
        long fileLength, modifyTime;
        var cached = NekoMaid.INSTANCE.pluginStaticFiles.get(path);
        if (cached == null) {
            File file = null;
            var result = path.split(File.separator.equals("\\") ? "\\\\" : File.separator, 2);
            if (result.length == 2) {
                var list = NekoMaid.INSTANCE.pluginStaticPaths.get(result[0]);
                if (list != null) for (var dir : list) {
                    var tmp = new File(dir, result[1]);
                    if (!tmp.isHidden() && tmp.isFile()) {
                        file = tmp;
                        break;
                    }
                }
            }

            if (file == null) {
                sendError(ctx, NOT_FOUND);
                return;
            }

            modifyTime = file.lastModified();
            if (sendModifyTime(modifyTime)) sendNotModified(ctx);

            try {
                raf = new RandomAccessFile(file, "r");
            } catch (FileNotFoundException ignore) {
                return;
            }
            fileLength = raf.length();
        } else {
            modifyTime = cached.getValue();
            fileLength = cached.getKey().length;
        }

        var response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpUtil.setContentLength(response, fileLength);

        setContentTypeHeader(response, path);
        setDateAndCacheHeaders(response, modifyTime);

        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        } else if (request.protocolVersion().equals(HTTP_1_0)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        ctx.write(response);

        ctx.write(raf == null ? Unpooled.wrappedBuffer(cached.getKey())
                : new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
        var lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        if (!keepAlive) lastContentFuture.addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (ctx.channel().isActive()) sendError(ctx, INTERNAL_SERVER_ERROR);
    }

    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");

    private boolean sendModifyTime(long time) throws ParseException {
        var ifModifiedSince = request.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            var dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
            var ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);

            var ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
            var fileLastModifiedSeconds = time / 1000;
            return ifModifiedSinceDateSeconds == fileLastModifiedSeconds;
        }
        return false;
    }

    private static String sanitizeUri(String uri) {
        uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);

        if (uri.isEmpty() || uri.charAt(0) != '/') {
            return null;
        }

        // Convert file separators.
        uri = uri.replace('/', File.separatorChar);

        // Simplistic dumb security check.
        // You will have to do something serious in the production environment.
        if (uri.contains(File.separator + '.') ||
                uri.contains('.' + File.separator) ||
                uri.charAt(0) == '.' || uri.charAt(uri.length() - 1) == '.' ||
                INSECURE_URI.matcher(uri).matches()) {
            return null;
        }

        // Convert to absolute path.
        return uri.substring(1);
    }

//    private void sendRedirect(ChannelHandlerContext ctx, String newUri) {
//        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, FOUND, Unpooled.EMPTY_BUFFER);
//        response.headers().set(HttpHeaderNames.LOCATION, newUri);
//
//        sendAndCleanupConnection(ctx, response);
//    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        var response = new DefaultFullHttpResponse(
                HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

        sendAndCleanupConnection(ctx, response);
    }

    private void sendNotModified(ChannelHandlerContext ctx) {
        var response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED, Unpooled.EMPTY_BUFFER);
        setDateHeader(response);

        sendAndCleanupConnection(ctx, response);
    }

    private void sendAndCleanupConnection(ChannelHandlerContext ctx, FullHttpResponse response) {
        final var request = this.request;
        final var keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        if (!keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        } else if (request.protocolVersion().equals(HTTP_1_0)) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        var flushPromise = ctx.writeAndFlush(response);

        if (!keepAlive) flushPromise.addListener(ChannelFutureListener.CLOSE);
    }

    private static void setDateHeader(FullHttpResponse response) {
        var dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        var time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));
    }

    private static void setDateAndCacheHeaders(HttpResponse response, long modifyTime) {
        var dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        var time = new GregorianCalendar();
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(time.getTime()));

        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.EXPIRES, dateFormatter.format(time.getTime()));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(
                HttpHeaderNames.LAST_MODIFIED, dateFormatter.format(new Date(modifyTime)));
    }

    private static void setContentTypeHeader(HttpResponse response, String ext) {
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(ext));
    }
}
