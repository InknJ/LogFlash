package templatemining;

import TCFGmodel.ShareMemory;
import TCFGmodel.TCFG;
import modelconstruction.MetricsMonitoring;
import org.apache.flink.api.common.accumulators.IntCounter;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple7;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.io.*;
import java.util.*;

import joinery.DataFrame;
import dao.MysqlUtil;
import TCFGmodel.TCFGUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import workflow.CommandListener;
import workflow.Config;

public class Parse extends KeyedProcessFunction<String, Tuple2<String, String>, Tuple7<String, String, String, String, String, String, String>> {
    private ValueState<Node> parseTree;
    private final IntCounter templateNum = new IntCounter();
    private TCFGUtil tcfgUtil;
    private MetricsMonitoring metricsMonitoring;
    private CommandListener commandListener;
    private Logger LOG;

    @Override
    public void processElement(Tuple2<String, String> input,
                               Context ctx,
                               Collector<Tuple7<String, String, String, String, String, String, String>> out) throws Exception {
        ParameterTool parameterTool = ParameterTool.fromMap(Config.parameter);
        Node rootNode = parseTree.value() == null || Config.valueStates.get("parseTree") == 1? tcfgUtil.getParseTreeRegion() : parseTree.value();
        Config.valueStates.put("parseTree",0);
        String[] regex = parameterTool.get("regex").split("&");
        int depth = 3;
        int maxChild = 100;
        double st = 0.5;
        LogParser parser = new LogParser(regex, parameterTool.get("logFormat"), depth, maxChild, st);
        DataFrame<String> df_log = parser.load_data(input.f1, parameterTool.get("timeFormat"));
        if (df_log == null) return;
        List<String> logmessageL = Arrays.asList(parser.preprocess(df_log.get(0, "Content")).trim().split("[ ]"));
        LogCluster matchCluster = parser.treeSearch(rootNode, logmessageL);
        if (matchCluster == null) {
            LogCluster newCluster = new LogCluster(logmessageL, parser.getHash(String.join(" ", logmessageL)));
            parser.addSeqToPrefixTree(rootNode, newCluster);
            matchCluster = newCluster;
        } else {
            List<String> oldTemplate = matchCluster.getLogTemplate();
            List<String> newTemplate = parser.getTemplate(logmessageL, matchCluster.getLogTemplate());
            if (!String.join(" ", newTemplate).equals(String.join(" ", oldTemplate))) {
                matchCluster.setLogTemplate(newTemplate);
            }
        }
        parseTree.update(rootNode);
        List<String> log_template = new ArrayList<>();
        log_template.add(String.join(" ", matchCluster.getLogTemplate()));
        df_log.add("EventTemplate", log_template);
        String time = df_log.get(0, "Time");
        String level = df_log.get(0, "Level");
        String component = df_log.get(0, "Component");
        String content = df_log.get(0, "Content");
        String eventTemplate = df_log.get(0, "EventTemplate");
        String parameterList = parser.get_parameter_list(df_log);
        parameterList = "\"" + parameterList + "\"";
        String eventID = matchCluster.getEventID();
        Tuple7<String, String, String, String, String, String, String> tuple = new Tuple7<>(time, level, component, content, eventTemplate, parameterList, eventID);
        tuple.f2 = "1";
        out.collect(tuple);
        templateNum.add(1);
//        parser.printTree(rootNode,0);
        if (parameterTool.getBoolean("printLog")) LOG.info(input.f1);
        if (templateNum.getLocalValue() % parameterTool.getInt("logInterval") == 0) {
            LOG.info("已接收日志数量：{}", templateNum.getLocalValue());
            FileWriter oo = new FileWriter(new File(parameterTool.get("templateFilePath")));
            Map<String, String> map1 = new HashMap<>();
            map1 = parser.saveTemplate(rootNode, 0, map1);
            for (Map.Entry<String, String> m : map1.entrySet()) {
                oo.write(m.getKey() + ',');
                oo.write(m.getValue() + '\n');
            }
            oo.close();
        }
    }

    @Override
    public void open(Configuration config) throws Exception {
        ValueStateDescriptor<Node> descriptor_parseTree =
                new ValueStateDescriptor<>(
                        "parseTree",
                        Node.class
                );
        parseTree = getRuntimeContext().getState(descriptor_parseTree);
        getRuntimeContext().addAccumulator("templateNum", templateNum);
        Config.parameter = new HashMap<String, String>() {{
            for (Entry<String, String> value : ParameterTool.fromPropertiesFile("src/main/resources/config.properties").toMap().entrySet()) {
                put(value.getKey(), value.getValue());
            }
        }};
        LOG = LoggerFactory.getLogger(Parse.class);
        TCFG.sm = new ShareMemory(Config.parameter.get("shareMemoryFilePath"), "TCFG");
        tcfgUtil = new TCFGUtil();
        //tcfgUtil.cleanShareMemory();
        tcfgUtil.initiateShareMemory();
        tcfgUtil.initiateDatabase();
        //metricsMonitoring = new MetricsMonitoring();
        //metricsMonitoring.start();
        commandListener = new CommandListener();
        commandListener.start();
    }

    @Override
    public void close() throws Exception {
        if (templateNum.getLocalValue() == 0) return;
        tcfgUtil.saveParseTreeRegion(parseTree.value());
//        MysqlUtil sql = new MysqlUtil();
//        Map<String, String> map = new HashMap<>();
//        map = parser.saveTemplate(parseTree.value(), 0, map);
//        sql.insertTemplate(map);
        //metricsMonitoring.cancel();
        commandListener.cancel();
    }
}
