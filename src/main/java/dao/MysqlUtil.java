package dao;

import com.alibaba.fastjson.JSON;
import faultdiagnosis.Anomaly;
import org.apache.flink.api.java.tuple.Tuple7;
import org.apache.flink.api.java.utils.ParameterTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import workflow.Config;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class MysqlUtil {

    // MySQL 8.0 以上版本 - JDBC 驱动名及数据库 URL
    static final String JDBC_DRIVER = "com.mysql.cj.jdbc.Driver";
    static ParameterTool parameter;
    static String connectionString;
    private static final Logger LOG = LoggerFactory.getLogger(MysqlUtil.class);

    static {
        parameter = ParameterTool.fromMap(Config.parameter);
        String database = parameter.get("database");
        String databaseUrl = parameter.get("databaseUrl");
        connectionString = "jdbc:mysql://" + databaseUrl + "/" + database + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    }


    private String StamptoTime(String time, String pattern) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        time = formatter.format(Long.valueOf(time));
        return time;
    }

    public void createAnomalyLogTable() {
        try {
            LOG.info("begin createAnomalyLogTable...");
            Class.forName(JDBC_DRIVER);
            Connection dbConnection = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            String createTableSQL = "CREATE TABLE IF NOT EXISTS anomaly_log("
                    + "id INT(11) PRIMARY KEY NOT NULL AUTO_INCREMENT, "
                    + "timestart VARCHAR(100) NOT NULL, "
                    + "timeend VARCHAR(100) NOT NULL, "
                    + "unixtimestart VARCHAR(15) NOT NULL, "
                    + "unixtimeend VARCHAR(15) NOT NULL, "
                    + "level VARCHAR(20), "
                    + "component VARCHAR(500), "
                    + "content TEXT, "
                    + "template TEXT, "
                    + "paramlist TEXT, "
                    + "eventid VARCHAR(200), "
                    + "anomalylogs TEXT, "
                    + "anomalyrequest TEXT, "
                    + "anomalywindow VARCHAR(200), "
                    + "anomalytype VARCHAR(10), "
                    + "anomalytemplates VARCHAR(500), "
                    + "logsequence_json TEXT, "
                    + "tagged BOOLEAN"
                    + ")";
            PreparedStatement preparedStatement = dbConnection.prepareStatement(createTableSQL);
            preparedStatement.executeUpdate();
            preparedStatement.close();
            dbConnection.close();
            LOG.info("createAnomalyLogTable finished...");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void createTCFGTable() {
        try {
            LOG.info("begin createTCFGTable...");
            Class.forName(JDBC_DRIVER);
            Connection dbConnection = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            String createTableSQL = "CREATE TABLE IF NOT EXISTS tcfg(id INT(11) PRIMARY KEY NOT NULL,TCFG_json LONGTEXT)";
            PreparedStatement preparedStatement = dbConnection.prepareStatement(createTableSQL);
            preparedStatement.executeUpdate();
            preparedStatement.close();
            String insertTCFGSQL = "insert into tcfg (id,TCFG_json) values(1,null) ON DUPLICATE KEY UPDATE TCFG_json=null";
            PreparedStatement preparedStatement1 = dbConnection.prepareStatement(insertTCFGSQL);
            preparedStatement1.executeUpdate();
            preparedStatement1.close();
            dbConnection.close();
            LOG.info("createTCFGTable finished...");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void updateTCFG(String tcfg) {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "update tcfg set TCFG_json = ? where id=1";
            ps = conn.prepareStatement(sql);
            ps.setString(1, tcfg);
            //update database
            ps.executeUpdate();

            // 完成后关闭
            stmt.close();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }

    }

    public String getTCFG() {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "select TCFG_json from tcfg where id=1";
            ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            String res = "";
            while (rs.next()) {
                res = rs.getString(1);
            }
            // 完成后关闭
            stmt.close();
            conn.close();
            return res;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
        return null;
    }

    public List<AnomalyJSON> getAnomalies() {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        List<AnomalyJSON> res = new ArrayList<>();
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "select id,timestart,timeend,unixtimestart,unixtimeend,level,component,content,template,paramlist,eventid,anomalylogs,anomalyrequest,anomalywindow,anomalytype,anomalytemplates from anomaly_log";
            ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                AnomalyJSON json = new AnomalyJSON(
                        Integer.parseInt(rs.getString(1)),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getString(9),
                        rs.getString(10),
                        rs.getString(11),
                        rs.getString(12),
                        rs.getString(13),
                        rs.getString(14),
                        rs.getString(15),
                        rs.getString(16)
                );
                res.add(json);
            }
            // 完成后关闭
            stmt.close();
            conn.close();
            return res;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
        return null;
    }


    public void insertAnomaly(Anomaly anomaly) {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "insert into anomaly_log (timestart,timeend,unixtimestart,unixtimeend,level,component,content,template,paramlist,eventid,anomalylogs,anomalyrequest,anomalywindow,anomalytype,anomalytemplates, logsequence_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            ps = conn.prepareStatement(sql);
            List anomalylogslist = anomaly.getAnomalyLogList();
            String anomalytype = anomaly.getAnomalyType();
            Tuple7 logcontent = anomaly.getAnomalyLog();
            List anomalyrequestlist = anomaly.getSuspectedAnomalyRequest();
            String unixtimestart = (String) anomaly.getAnomalyLogList().get(0).f0;
            String timestart = StamptoTime(unixtimestart, "HH:mm:ss:SSS");
            String unixtimeend = (String) logcontent.f0;
            String timeend = StamptoTime(unixtimeend, "HH:mm:ss:SSS");
            String level = (String) logcontent.f1;
            String component = (String) logcontent.f2;
            String content = (String) logcontent.f3;
            String template = (String) logcontent.f4;
            String paramlist = (String) logcontent.f5;
            String eventid = (String) logcontent.f6;
            String anomalylogs = "";
            for (Object templog : anomalylogslist) {
                Tuple7 log = (Tuple7) templog;
                anomalylogs = anomalylogs + log.f3 + '\n';
            }
            String anomalyrequest = "";
            for (Object templog : anomalyrequestlist) {
                Tuple7 log = (Tuple7) templog;
                anomalyrequest = anomalyrequest + log.f3 + '\n';
            }
            String anomalyrequesttemplates = "";
            for (Object templog : anomalyrequestlist) {
                Tuple7 log = (Tuple7) templog;
                anomalyrequesttemplates = anomalyrequesttemplates + log.f6 + '\n';
            }
            String anomalywindow = "";
            String logsequence_json = JSON.toJSONString(anomaly);
            ps.setString(1, timestart);
            ps.setString(2, timeend);
            ps.setString(3, unixtimestart);
            ps.setString(4, unixtimeend);
            ps.setString(5, level);
            ps.setString(6, component);
            ps.setString(7, content);
            ps.setString(8, template);
            ps.setString(9, paramlist);
            ps.setString(10, eventid);
            ps.setString(11, anomalylogs);
            ps.setString(12, anomalyrequest);
            ps.setString(13, anomalywindow);
            ps.setString(14, anomalytype);
            ps.setString(15, anomalyrequesttemplates);
            ps.setString(16, logsequence_json);
            //ps.executeUpdate();

            // 完成后关闭
            stmt.close();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }

    public Anomaly getAnomalyByID(int id) {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "SELECT logsequence_json FROM anomaly_log WHERE id = ?";
            ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                return JSON.parseObject(rs.getString(1), Anomaly.class);
            }
            // 完成后关闭
            rs.close();
            stmt.close();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
        return null;
    }

    public String getFailureTypeByFaultId(String faultId) {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "SELECT failure_type FROM injection_record_hadoop WHERE fault_id = ?";
            ps = conn.prepareStatement(sql);
            ps.setString(1, faultId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String faultType = rs.getString(1);
                return faultType;
            }
            // 完成后关闭
            rs.close();
            stmt.close();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
        return null;
    }

    public String getActiviatFlagByFaultId(String faultId) {
        Connection conn = null;
        Statement stmt = null;
        PreparedStatement ps = null;
        try {
            // 注册 JDBC 驱动
            Class.forName(JDBC_DRIVER);
            // 打开链接
            conn = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            // 执行查询
            stmt = conn.createStatement();
            String sql = "SELECT activated FROM injection_record_hadoop WHERE fault_id = ?";
            ps = conn.prepareStatement(sql);
            ps.setString(1, faultId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String faultType = rs.getString(1);
                return faultType;
            }
            // 完成后关闭
            rs.close();
            stmt.close();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (stmt != null) stmt.close();
            } catch (SQLException se2) {
            }
            try {
                if (conn != null) conn.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
        return null;
    }

    public void insertTemplate(Map<String, String> map) {
        Connection conn;
        PreparedStatement ps;
        try {
            Class.forName(JDBC_DRIVER);
            conn = DriverManager.getConnection(connectionString + "&rewriteBatchedStatements=true", parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            String sql = "INSERT INTO template_log (id, template, number) VALUES(?,?,1) ON DUPLICATE KEY UPDATE number=number+1";
            ps = conn.prepareStatement(sql);
            for (Map.Entry<String, String> m : map.entrySet()) {
                ps.setString(1, m.getKey());
                ps.setString(2, m.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void truncateTables() {
        try {
            Class.forName(JDBC_DRIVER);
            Connection dbConnection = DriverManager.getConnection(connectionString, parameter.get("mysqlUser"), parameter.get("mysqlPassword"));
            String createTableSQL = "TRUNCATE table anomaly_log";
            PreparedStatement preparedStatement = dbConnection.prepareStatement(createTableSQL);
            preparedStatement.executeUpdate();
            preparedStatement.close();
            String insertTCFGSQL = "insert into tcfg (id,TCFG_json) values(1,null) ON DUPLICATE KEY UPDATE TCFG_json=null";
            PreparedStatement preparedStatement1 = dbConnection.prepareStatement(insertTCFGSQL);
            preparedStatement1.executeUpdate();
            preparedStatement1.close();
            dbConnection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
