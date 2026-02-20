package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Receives WeChat Official Account message callbacks.
 * Set the URL in WeChat Official Account admin to /api/wechat/webhook.
 * WeChat sends XML; we parse manually to avoid extra dependencies.
 * See https://developers.weixin.qq.com/doc/offiaccount/en/Message_Management/Receiving_standard_messages.html
 */
@RestController
@RequestMapping("/api/wechat")
public class WeChatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WeChatWebhookController.class);

    private final WeChatApiClient weChatApi;
    private final WeChatConfig.WeChatProperties properties;
    private final ChatService chatService;

    public WeChatWebhookController(WeChatApiClient weChatApi,
                                   WeChatConfig.WeChatProperties properties,
                                   ChatService chatService) {
        this.weChatApi = weChatApi;
        this.properties = properties;
        this.chatService = chatService;
    }

    /** Token verification (GET). WeChat sends signature, timestamp, nonce, echostr. */
    @GetMapping("/webhook")
    public ResponseEntity<String> verify(
            @RequestParam("signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echostr) {
        String[] arr = {properties.getToken(), timestamp, nonce};
        Arrays.sort(arr);
        String joined = String.join("", arr);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(joined.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            if (sb.toString().equals(signature)) {
                return ResponseEntity.ok(echostr);
            }
        } catch (Exception e) {
            log.warn("WeChat signature verification error: {}", e.getMessage());
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    /** Incoming message (POST, XML body). Reply with XML or use customer service API. */
    @PostMapping(value = "/webhook", consumes = MediaType.TEXT_XML_VALUE, produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> webhook(@RequestBody String xmlBody) {
        if (!weChatApi.isConfigured()) return ResponseEntity.ok("");

        String fromUser = extractXmlTag(xmlBody, "FromUserName");
        String toUser = extractXmlTag(xmlBody, "ToUserName");
        String msgType = extractXmlTag(xmlBody, "MsgType");
        String content = extractXmlTag(xmlBody, "Content");

        if (!"text".equals(msgType) || fromUser == null || content == null) {
            return ResponseEntity.ok("");
        }

        String reply = chatService.getReply(content);
        long timestamp = System.currentTimeMillis() / 1000;
        String xml = "<xml>"
                + "<ToUserName><![CDATA[" + fromUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + toUser + "]]></FromUserName>"
                + "<CreateTime>" + timestamp + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + reply + "]]></Content>"
                + "</xml>";
        return ResponseEntity.ok(xml);
    }

    private String extractXmlTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String openCdata = "<" + tag + "><![CDATA[";
        String closeCdata = "]]></" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(openCdata);
        if (start >= 0) {
            start += openCdata.length();
            int end = xml.indexOf(closeCdata, start);
            return end >= 0 ? xml.substring(start, end) : null;
        }
        start = xml.indexOf(open);
        if (start >= 0) {
            start += open.length();
            int end = xml.indexOf(close, start);
            return end >= 0 ? xml.substring(start, end) : null;
        }
        return null;
    }
}
