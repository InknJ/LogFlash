package templatemining;

import joinery.DataFrame;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser implements Serializable {
    private final String log_format;
    private final String[] rex;
    private final int depth;
    private final int maxChild;
    private final double st;

    LogParser(String[] rex, String log_format, int depth, int maxChild, double st) {
        this.rex = rex;
        this.log_format = log_format;
        this.depth = depth - 2;
        this.maxChild = maxChild;
        this.st = st;
    }

    private Map<String, Object> generate_logFormat_regex(String log_format) {
        List<String> headers = new ArrayList<>();
        List<String> splitters = new ArrayList<>();
        StringBuilder regex = new StringBuilder();
        String[] s = log_format.split("(<[^<>]+>)");
        Pattern p = Pattern.compile("(<[^<>]+>)");
        Matcher m = p.matcher(log_format);
        for (int i = 0; m.find(); i++) {
            splitters.add(s[i]);
            splitters.add(m.group());
        }
        int length = splitters.size();
        for (int i = 0; i < length; i++) {
            if (i % 2 == 0) {
                String splitter = splitters.get(i).replaceAll(" +", "\\\\s+");
                if (splitter.contains("["))
                    splitter = splitter.replaceAll("\\[", "\\\\[");
                if (splitter.contains("]"))
                    splitter = splitter.replaceAll("]", "\\\\]");
                regex.append(splitter);
            } else {
                String header = splitters.get(i).replace("<", "").replace(">", "");
                headers.add(header);
                if (header.equals("Content"))
                    regex.append(String.format("(?<%s>.*)", header));
                else if (header.equals("RequestID"))
                    regex.append(String.format("(?<%s>.*?)", header));
                else
                    regex.append(String.format("(?<%s>[^\\s]+)", header));
            }
        }
        Pattern re = Pattern.compile("^" + regex + "$");
        Map<String, Object> map = new HashMap<>();
        map.put("headers", headers);
        map.put("regex", re);
        return map;
    }

    public String TimetoStamp(String time, String pattern) throws ParseException {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        time = String.valueOf(formatter.parse(time).getTime());
        return time;
    }

    private DataFrame<String> s_to_dataFrame(String s, Pattern regex, List<String> headers, String timeFormat) {
        DataFrame<String> logdf = new DataFrame<>(headers);
        Matcher m = regex.matcher(s.trim());
        if (m.find()) {
            List<String> message = new ArrayList<>();
            for (String header : headers) {
                String t = m.group(header);
                if (header.equals("Time")) {
                    try {
                        if (headers.contains("Date")) {
                            t = TimetoStamp(message.get(0) + " " + t, "yyyy-MM-dd " + timeFormat);
                        } else
                            t = TimetoStamp(t, timeFormat);
                    } catch (ParseException e) {
                        return new DataFrame<>(headers);
                    }
                }
                message.add(t);
            }
            logdf.append(message);
        }
        return logdf;
    }

    DataFrame<String> load_data(String s, String timeFormat) {
        String[] l = log_format.split("@");
        String[] t = timeFormat.split("@");
        int length = l.length;
        for (int i = 0; i < length; i++) {
            Map<String, Object> map = generate_logFormat_regex(l[i]);
            List<String> headers = (List<String>) map.get("headers");
            Pattern regex = (Pattern) map.get("regex");
            DataFrame<String> logdf = s_to_dataFrame(s, regex, headers, t[i]);
            if (!logdf.isEmpty()) {
                return logdf;
            }
        }
        return null;
    }

    String preprocess(String line) {
        for (String currentRex : rex) {
            line = line.replaceAll(currentRex, "<*>");
        }
        return line;
    }

    // seq1 is template
    private Map<String, Double> seqDist(List<String> seq1, List<String> seq2) {
        double simTokens = 0.0;
        double numOfPar = 0.0;
        double retVal = 1.0;
        if (!seq1.equals(seq2)) {
            for (int i = 0; i < seq1.size(); i++) {
                String token1 = seq1.get(i);
                String token2 = seq2.get(i);
                if (token1.equals("<*>")) {
                    numOfPar += 1;
                    continue;
                }
                if (token1.equals(token2)) {
                    simTokens += 1;
                }
            }
            retVal = simTokens / seq1.size();
        }
        Map<String, Double> map = new HashMap<>();
        map.put("retVal", retVal);
        map.put("numOfPar", numOfPar);
        return map;
    }

    private LogCluster fastMatch(List<LogCluster> logClustL, List<String> seq) {
        LogCluster retLogClust = null;
        LogCluster maxClust = null;
        double maxSim = -1;
        double maxNumOfPara = -1;
        for (LogCluster logClust : logClustL) {
            Map<String, Double> map = seqDist(logClust.getLogTemplate(), seq);
            double curSim = map.get("retVal");
            double curNumOfPara = map.get("numOfPar");
            if (curSim > maxSim || (curSim == maxSim && curNumOfPara > maxNumOfPara)) {
                maxSim = curSim;
                maxNumOfPara = curNumOfPara;
                maxClust = logClust;
            }
        }
        if (maxSim >= st) {
            retLogClust = maxClust;
        }
        return retLogClust;
    }

    LogCluster treeSearch(Node rn, List<String> seq) {
        LogCluster retLogClust;
        int seqLen = seq.size();

        if (!rn.getChildD().containsKey(String.valueOf(seqLen))) {
            return null;
        }
        Node parentn = rn.getChildD().get(String.valueOf(seqLen));
        int currentDepth = 1;
        for (String token : seq) {
            if (currentDepth > depth || currentDepth > seqLen) {
                break;
            }
            if (parentn.getChildD().containsKey(token)) {
                parentn = parentn.getChildD().get(token);
            } else if (parentn.getChildD().containsKey("<*>")) {
                parentn = parentn.getChildD().get("<*>");
            } else {
                return null;
            }
            currentDepth += 1;
        }
        List<LogCluster> logClustL = parentn.getChildLG();
        retLogClust = fastMatch(logClustL, seq);
        return retLogClust;
    }

    private boolean hasNumbers(String content) {
        boolean flag = false;
        Pattern p = Pattern.compile(".*\\d+.*");
        Matcher m = p.matcher(content);
        if (m.matches()) {
            flag = true;
        }
        return flag;
    }

    void addSeqToPrefixTree(Node rn, LogCluster logClust) {
        int seqLen = logClust.getLogTemplate().size();
        Node firstLayerNode;
        if (!rn.getChildD().containsKey(String.valueOf(seqLen))) {
            firstLayerNode = new Node(1, String.valueOf(seqLen));
            rn.setChildD(String.valueOf(seqLen), firstLayerNode);
        } else {
            firstLayerNode = rn.getChildD().get(String.valueOf(seqLen));
        }
        Node parentn = firstLayerNode;
        int currentDepth = 1;
        for (String token : logClust.getLogTemplate()) {
            if (!parentn.getChildD().containsKey(token)) {
                if (!hasNumbers(token)) {
                    if (parentn.getChildD().containsKey("<*>")) {
                        if (parentn.getChildD().size() < maxChild) {
                            Node newNode = new Node(currentDepth + 1, token);
                            parentn.setChildD(token, newNode);
                            parentn = newNode;
                        } else {
                            parentn = parentn.getChildD().get("<*>");
                        }
                    } else {
                        if (parentn.getChildD().size() + 1 < maxChild) {
                            Node newNode = new Node(currentDepth + 1, token);
                            parentn.setChildD(token, newNode);
                            parentn = newNode;
                        } else if (parentn.getChildD().size() + 1 == maxChild) {
                            Node newNode = new Node(currentDepth + 1, "<*>");
                            parentn.setChildD("<*>", newNode);
                            parentn = newNode;
                        } else {
                            parentn = parentn.getChildD().get("<*>");
                        }
                    }
                } else {
                    if (!parentn.getChildD().containsKey("<*>")) {
                        Node newNode = new Node(currentDepth + 1, "<*>");
                        parentn.setChildD("<*>", newNode);
                        parentn = newNode;
                    } else {
                        parentn = parentn.getChildD().get("<*>");
                    }
                }
            } else {
                parentn = parentn.getChildD().get(token);
            }
            currentDepth += 1;
            if (currentDepth > depth || currentDepth > seqLen) {
                parentn.setChildLG(logClust);
                break;
            }
        }
    }

    List<String> getTemplate(List<String> seq1, List<String> seq2) {
        List<String> retVal = new ArrayList<>();
        int i = 0;
        for (String word : seq1) {
            if (word.equals(seq2.get(i))) {
                retVal.add(word);
            } else {
                retVal.add("<*>");
            }
            i += 1;
        }
        return retVal;
    }

    String getHash(String template) {
        String resultStr = "";
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(template.getBytes());
            BigInteger bigInt = new BigInteger(1, md5.digest());
            resultStr = bigInt.toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return resultStr.substring(0, 8);
    }

    String get_parameter_list(DataFrame<String> df_log) {
        String template_regex = df_log.get(0, "EventTemplate").replaceAll("<.{1,5}>", "<*>");
        if (!template_regex.contains("<*>")) return "";
        template_regex = template_regex.replaceAll("([^A-Za-z0-9])", "\\\\$0");
        template_regex = template_regex.replaceAll(" +", "\\s+");
        template_regex = "^" + template_regex.replaceAll("\\\\<\\\\\\*\\\\>", "(.*?)") + "$";
        Pattern p = Pattern.compile(template_regex);
        Matcher m = p.matcher(df_log.get(0, "Content"));
        List<String> parameter_list = new ArrayList<>();
        if (m.find()) {
            int num = m.groupCount();
            for (int i = 1; i <= num; i++) {
                parameter_list.add(m.group(i));
            }
        }
        return parameter_list.toString();
    }

    void printTree(Node node, int dep) {
        StringBuilder pStr = new StringBuilder();
        for (int i = 0; i < dep; i++) {
            pStr.append('\t');
        }
        if (node.getDepth() == 0)
            pStr.append("Root");
        else if (node.getDepth() == 1)
            pStr.append('<').append(node.getDigitOrtoken()).append('>');
        else
            pStr.append(node.getDigitOrtoken());
        System.out.println(pStr);
        if (node.getDepth() == depth + 1) {
            List<LogCluster> lg = node.getChildLG();
            for (LogCluster t : lg) {
                pStr = new StringBuilder();
                for (int i = 0; i <= dep; i++) {
                    pStr.append('\t');
                }
                System.out.println(pStr.append(t.getLogTemplate()));
            }
        }
        for (Map.Entry<String, Node> entry : node.getChildD().entrySet())
            printTree(entry.getValue(), dep + 1);
    }

    Map<String, String> saveTemplate(Node node, int dep, Map<String, String> map) {
        if (node.getDepth() == 1 && node.getDigitOrtoken().equals("1")) {
            for (Map.Entry<String, Node> entry : node.getChildD().entrySet()) {
                List<LogCluster> lg = entry.getValue().getChildLG();
                for (LogCluster t : lg) {
                    String template = String.join(" ", t.getLogTemplate());
                    map.put(getHash(template), template);
                }
            }
        }
        if (node.getDepth() == depth + 1) {
            List<LogCluster> lg = node.getChildLG();
            for (LogCluster t : lg) {
                String template = String.join(" ", t.getLogTemplate());
                map.put(getHash(template), template);
            }
        }
        for (Map.Entry<String, Node> entry : node.getChildD().entrySet())
            map = saveTemplate(entry.getValue(), dep + 1, map);
        return map;
    }
}
