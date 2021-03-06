package com.nathan2055.reddithistory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kevinsawicki.http.HttpRequest;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4;

public class RedditHistory {

    public static final Integer LIMIT = 100;
    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Set<String> SAVE_TYPES = new LinkedHashSet<>(Arrays.asList(
            "overview", "saved", "comments", "submitted"));
    static final Logger log = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    @Option(name = "-loglevel", usage = "Sets the log level [INFO, DEBUG, etc.]")
    private final String loglevel = "INFO";
    @Option(name = "-sort", usage = "The sort order (top, new)")
    private final String sort = "top";
    @Option(name = "-username", usage = "Your username", required = true)
    private String username;
    @Option(name = "-password", usage = "Your password", required = true)
    private String password;
    @Option(name = "-client_id", usage = "Your client id", required = true)
    private String clientId;
    @Option(name = "-client_secret", usage = "Your client secret", required = true)
    private String clientSecret;
    @Option(name = "-user", usage = "The user you want to query", required = true)
    private String user;

    public static void fetcher(String accessToken, String user, String endpoint, String sort) throws Exception {

        File saveFile = new File("reddit_history", "reddit_" + user + "_" + endpoint + "_" + sort + ".md");
        log.info("file: " + saveFile.getAbsolutePath());

        String after = null;
        int i = 0;
        while (after != null || i == 0) {
            JsonNode node = fetchJson(accessToken, user, endpoint, sort, after);
            ++i;
            if (node != null && !node.has("error")) {
                log.debug(convertNodeToJson(node));
                after = node.has("data") ? node.get("data").get("after").asText() : "null";
                if (after.equals("null")) after = null;
                List<Data> list = extractData(node);
                saveData(saveFile, convertListToMarkdown(list));
                Thread.sleep(1050);
                log.info("fetch count = " + i);
                log.debug("after = " + after);
            }
        }
    }

    // example: https://github.com/reddit/reddit/wiki/OAuth2-Quick-Start-Example
    public static String getAccessToken(String username, String password, String clientId, String clientSecret) throws Exception {

        HttpRequest req = HttpRequest.post("https://www.reddit.com/api/v1/access_token")
                .basic(clientId, clientSecret)
                .header("User-Agent", "reddit-history/0.1 by " + username)
                .form("grant_type", "password")
                .form("username", username)
                .form("password", password);

        String body = req.body();
        log.info(body);

        return convertJsonToNode(body).get("access_token").asText();
    }

    public static JsonNode fetchJson(String accessToken, String user, String endpoint, String sort, String after) throws Exception {
        //        https://oauth.reddit.com/user/asdf/comments.json?after=AFTER_ID&limit=100

        Map<String, String> params = new HashMap<>();
        params.put("limit", LIMIT.toString());
        params.put("sort", sort);

        if (after != null) {
            params.put("after", after);
        }

        HttpRequest req = HttpRequest.get("https://oauth.reddit.com/user/" + user + "/" + endpoint + ".json", params, true)
                .header("Authorization", "bearer " + accessToken)
                .header("User-Agent", "reddit-history/0.1 by " + user);

        printRateLimits(req);

        return convertJsonToNode(req.body());
    }

    public static String convertListToMarkdown(List<Data> list) {

        StringBuilder sb = new StringBuilder();

        for (Data data : list) {
            sb.append(data.toMarkdown());
        }

        return sb.toString();
    }

    public static List<Data> extractData(JsonNode node) {

        List<Data> list = new ArrayList<>();
        JsonNode children = node.get("data").get("children");
        for (JsonNode child : children) {
            JsonNode d = child.get("data");

            String kind = child.get("kind").asText();
            String type_ = kind.equals("t1") ? "Comment" : "Link";
            String subreddit = d.get("subreddit").asText();
            String linkTitle = d.has("link_title") ?
                    d.get("link_title").asText() :
                    d.get("title").asText();
            String linkUrl = d.has("link_id") ?
                    "https://reddit.com/r/" + subreddit + "/" + d.get("link_id").asText().split("_")[1] :
                    "https://reddit.com/r/" + subreddit + "/" + d.get("id").asText();
            String body = d.has("body") ?
                    d.get("body").asText() :
                    d.get("selftext").asText();
            Integer score = d.get("score").asInt();
            list.add(new Data(type_, subreddit, linkTitle, linkUrl, unescapeHtml4(body), score));
        }

        return list;
    }

    public static void printRateLimits(HttpRequest req) {
        log.debug("rate remaining : " + req.header("X-Ratelimit-Remaining"));
        log.debug("rate used : " + req.header("X-Ratelimit-Used"));
        log.debug("rate reset : " + req.header("X-Ratelimit-Reset"));
    }

    public static void saveData(File f, String data) {
        try {
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            if (!f.exists()) f.createNewFile();
            Files.write(f.toPath(), data.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String convertNodeToJson(JsonNode node) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    public static JsonNode convertJsonToNode(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    public static void main(String[] args) throws Exception {
        new RedditHistory().doMain(args);
    }

    public void doMain(String[] args) throws Exception {

        parseArguments(args);

        log.setLevel(Level.toLevel(loglevel));

        String accessToken = getAccessToken(username, password, clientId, clientSecret);

        for (String saveType : SAVE_TYPES) {
            fetcher(accessToken, user, saveType, sort);
        }

    }

    private void parseArguments(String[] args) {
        CmdLineParser parser = new CmdLineParser(this);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // if there's a problem in the command line,
            // you'll get this exception. this will report
            // an error message.
            System.err.println(e.getMessage());
            System.err.println("java -jar reddit-history.jar [options...] arguments...");
            // print the list of available options
            parser.printUsage(System.err);
            System.err.println();
            System.exit(0);

        }
    }

    public static class Data {
        private final String linkUrl, body, subreddit, linkTitle, type_;
        private Integer score;

        public Data(String type_, String subreddit, String linkTitle, String linkUrl, String body, Integer score) {
            this.linkUrl = linkUrl;
            this.body = body;
            this.linkTitle = linkTitle;
            this.subreddit = subreddit;
            this.type_ = type_;
            this.score = score;
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("Type: ").append(type_).append("\n\n").append("subreddit: " + "[").append(subreddit).append("](https://reddit.com/r/").append(subreddit).append(")\n\n").append("[").append(linkTitle).append("](").append(linkUrl).append(")\n\n").append("Score: ").append(score).append("\n\n").append(body).append("\n\n---\n\n");

            return sb.toString();
        }

        public String getLinkUrl() {
            return linkUrl;
        }

        public String getBody() {
            return body;
        }

        public String getSubreddit() {
            return subreddit;
        }

        public String getLinkTitle() {
            return linkTitle;
        }

        public String getType_() {
            return type_;
        }

        public Integer getScore() {
            return score;
        }
    }

}
