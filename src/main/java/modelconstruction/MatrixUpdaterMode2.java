package modelconstruction;

import TCFGmodel.TCFGUtil;
import faultdiagnosis.Anomaly;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple7;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import workflow.Config;

import java.util.*;

import static humanfeedback.SuspiciousRegionMonitor.*;


public class MatrixUpdaterMode2 implements MatrixUpdater {

    private List<Tuple7> getTimeWindowLogList(long startTime, List<Tuple7> logList) {
        List<Tuple7> timeWindowLogList = new ArrayList();
        for (Tuple7 tuple: logList) {
            if (Long.parseLong((String)tuple.f0) > startTime) {
                timeWindowLogList.add(tuple);
            }
        }
        return timeWindowLogList;
    }

    @Override
    public double calGradientForInfected(long ti, long tj, TransferParamMatrix transferParamMatrix, List<Tuple7> tempList, long timeWindow, long delta) {

        if (tempList.size() < 2) {
            return 0;
        }
        if (ti-tj <= delta) {
            tj = ti-delta-1;
        }
        double minuend = Math.log(((TCFGUtil.minMax((double)(ti-tj),delta,timeWindow))*delta+delta)/delta);
        double sum = 0;
        Tuple7 elementi = tempList.get(tempList.size()-1);
        for (int i=0; i < tempList.size()-1; i++) {
            Tuple7 elementk = tempList.get(i);
            if (Long.parseLong((String)elementi.f0) - Long.parseLong((String)elementk.f0) <= delta) {
                elementk.f0 = String.valueOf(Long.parseLong((String)elementi.f0) - delta -1);
            }
            double alphaki = transferParamMatrix.getParam((String)elementk.f6,(String)elementi.f6);
            sum = sum + alphaki/((TCFGUtil.minMax(Long.parseLong((String)elementi.f0) - Long.parseLong((String)elementk.f0),delta,timeWindow))*delta+delta);
        }
        if (sum == 0) {
            return -1;
        }
        double subtrahend = 1/((TCFGUtil.minMax((double)(ti-tj),delta,timeWindow))*delta+delta)/sum;
        return minuend-subtrahend;
    }

    @Override
    public double calGradientForUninfected(long ti, long tj, TransferParamMatrix transferParamMatrix, List<Tuple7> tempList, long delta) {
        return 0;
    }

    public static class TransferParamMatrixUpdate extends ProcessWindowFunction<Tuple7<String, String, String, String, String, String, String>, String, String, TimeWindow> {

        private ValueState<TransferParamMatrix> transferParamMatrix;
        private ValueState<TCFGUtil.counter> counterValueState;

        @Override
        public void process(String s, Context context, Iterable<Tuple7<String, String, String, String, String, String, String>> input, Collector<String> out) throws Exception {

            ParameterTool parameterTool = ParameterTool.fromMap(Config.parameter);
            TCFGUtil tcfgUtil = new TCFGUtil();
            long slidingWindowStep = parameterTool.getLong("slidingWindowStep");

            //Initialize paramMatrix and counter
            TransferParamMatrix tempTransferParamMatrix = transferParamMatrix.value();
            if (tempTransferParamMatrix == null || Config.valueStates.get("transferParamMatrix") == 1) {
                tempTransferParamMatrix = tcfgUtil.getMatrixFromMemory();
                transferParamMatrix.update(tempTransferParamMatrix);
                Config.valueStates.put("transferParamMatrix",0);
            }
            TCFGUtil.counter counter = counterValueState.value();
            if (counter == null) {
                counter = new TCFGUtil().new counter();
            }
            //Update transferParamMatrix in share memory
            if (counter.modResult(parameterTool.getInt("matrixWriteInterval")) == 0) {
                try {
                    //handle human feedback
                    List<String> priorEventIDList = tempTransferParamMatrix.getEventIDList();
                    while (!tuningRegion.isEmpty()) {
                        Anomaly anomaly = tuningRegion.pollAnomalyFromQueue();
                        Tuple7 in = anomaly.getAnomalyLog();
                        if (anomaly.getAnomalyType() == "Latency") {
                            List<Tuple7> tempList = TCFGUtil.deleteReplica(anomaly.getAnomalyLogList());
                            for (Tuple7 tuple: tempList) {
                                if (!priorEventIDList.contains(tuple.f6))
                                    continue;
                                tempTransferParamMatrix.updateTimeWeight((String)in.f6,(String)tuple.f6,Long.parseLong((String)in.f0)- Long.parseLong((String)tuple.f0));
                            }
                        }
                        if (anomaly.getAnomalyType() == "Redundancy") {
                            tempTransferParamMatrix.addNewTemplate((String)in.f6,(String)in.f4,Long.valueOf((String)in.f0));
                        }
                    }
                    //delete expired templates
                    Random rand = new Random();
                    int checkIndex = rand.nextInt(priorEventIDList.size());
                    Tuple2<String, Long> tupleInfo = tempTransferParamMatrix.getEventInfo().get(priorEventIDList.get(checkIndex));
                    long windowTime = context.window().getStart();
                    if (windowTime > tupleInfo.f1.longValue() + parameterTool.getLong("expireTime")) {
                        tempTransferParamMatrix.deleteExpiredTemplate(priorEventIDList.get(checkIndex));
                    }
                    //update share memory
                    tcfgUtil.saveMatrixInMemory(tempTransferParamMatrix);
                    //decay
                    tempTransferParamMatrix.decay(parameterTool.getDouble("beta"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            counterValueState.update(counter);
//           int trainingFlag = parameterTool.getInt("trainingFlag");
            //添加一个线程监控matrix的Frobenius norm，如果该数值保持稳定则将trainingFlag调整为0
            int trainingFlag = tcfgUtil.getTrainingFlag();
            //System.out.println(tempTransferParamMatrix.checkConsistency());
            //TCFG Construction process
            List<String> priorEventIDList = tempTransferParamMatrix.getEventIDList();
            List<Tuple7> tempList = new ArrayList<>();
            Iterator<Tuple7<String, String, String, String, String, String, String>> iter = input.iterator();
            if (iter.hasNext()) {
                while (iter.hasNext()) {
                    Tuple7 in = iter.next();
                    if (trainingFlag == 0 && !priorEventIDList.contains(in.f6)) {
                        continue;
                    }
                    //add new template into the matrix during training
                    if (trainingFlag == 1 && !priorEventIDList.contains(in.f6)) {
                        tempTransferParamMatrix.addNewTemplate((String) in.f6, (String) in.f4, Long.valueOf((String)in.f0));
                    }else {
                        tempTransferParamMatrix.updateOccurTime((String) in.f6, (String) in.f4, Long.valueOf((String)in.f0));
                    }
                    long inTime = Long.parseLong((String) in.f0);
                    //add new template to matrix update process during training
                    if (trainingFlag == 1 || priorEventIDList.contains(in.f6)) {
                        tempList.add(in);
                    }
                    tempList = TCFGUtil.deleteReplica(tempList);
                    //tempList = new IndenpendencyFilter().filterIndependentNodes(tempList, tempTransferParamMatrix, slidingWindowStep, parameterTool.getLong("delta"));
                    if (inTime - context.window().getStart() > slidingWindowStep) {
                        MatrixUpdaterMode2 TCFGConstructer = new MatrixUpdaterMode2();
                        List<Tuple7> slidingWindowList = TCFGConstructer.getTimeWindowLogList(inTime - slidingWindowStep, tempList);
                        //Start grad computation
                        for (Tuple7 tuple : slidingWindowList) {
//                            if (slidingWindowList.indexOf(tuple) == slidingWindowList.size() - 1) {
//                                break;
//                            }
                            if (!tempTransferParamMatrix.getEventIDList().contains(in.f6)) {
                                break;
                            }
                            //update param matrix during training
                            double gradient = TCFGConstructer.calGradientForInfected(inTime, Long.parseLong((String) tuple.f0), tempTransferParamMatrix, slidingWindowList, slidingWindowStep, parameterTool.getLong("delta"));
                            if (gradient > parameterTool.getDouble("gradLimitation")) {
                                gradient = parameterTool.getDouble("gradLimitation");
                            }
                            if (gradient < -parameterTool.getDouble("gradLimitation")) {
                                gradient = -parameterTool.getDouble("gradLimitation");
                            }
                            if (gradient != 0) {
                                tempTransferParamMatrix.updateParam(parameterTool.getDouble("gamma"),parameterTool.getDouble("alpha"),(String) tuple.f6, (String) in.f6, gradient);
                            }
                            //Update Time Weight
                            if (trainingFlag == 1) {
                                tempTransferParamMatrix.updateTimeWeight((String) tuple.f6, (String) in.f6, inTime - Long.parseLong((String) tuple.f0));
                            }
                        }
                    }
                }
                transferParamMatrix.update(tempTransferParamMatrix);
            }

        }

        @Override
        public void open(Configuration parameters) throws Exception {
            ValueStateDescriptor<TransferParamMatrix> descriptor =
                    new ValueStateDescriptor<>(
                            "transferParamMatrix", // the state name
                            TransferParamMatrix.class // type information
                    );
            transferParamMatrix = getRuntimeContext().getState(descriptor);

            ValueStateDescriptor<TCFGUtil.counter> descriptor1 =
                    new ValueStateDescriptor<>(
                            "counterValueState", // the state name
                            TCFGUtil.counter.class // type information
                    );
            counterValueState = getRuntimeContext().getState(descriptor1);
            super.open(parameters);

        }

        @Override
        public void close() throws Exception {
            super.close();
        }
    }
}
