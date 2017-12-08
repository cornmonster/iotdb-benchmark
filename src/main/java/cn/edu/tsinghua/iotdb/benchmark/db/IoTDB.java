package cn.edu.tsinghua.iotdb.benchmark.db;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.edu.tsinghua.iotdb.benchmark.conf.Config;
import cn.edu.tsinghua.iotdb.benchmark.conf.ConfigDescriptor;
import cn.edu.tsinghua.iotdb.benchmark.conf.Constants;
import cn.edu.tsinghua.iotdb.benchmark.function.Function;
import cn.edu.tsinghua.iotdb.benchmark.function.FunctionParam;
import cn.edu.tsinghua.iotdb.benchmark.loadData.Point;
import cn.edu.tsinghua.iotdb.jdbc.TsfileJDBCConfig;

public class IoTDB implements IDatebase {
	private static final Logger LOGGER = LoggerFactory.getLogger(IoTDB.class);
	private static final String createStatementSQL = "create timeseries %s with datatype=DOUBLE,encoding=%s";
	private static final String createStatementFromFileSQL = "create timeseries %s with datatype=%s,encoding=%s";
	private static final String setStorageLevelSQL = "set storage group to %s";
	private Connection connection;
	private Config config;
	private List<Point> points;
	private Map<String, String> mp;
	private long labID;
	private MySqlLog mySql;
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public IoTDB(long labID) throws ClassNotFoundException, SQLException {
		Class.forName(TsfileJDBCConfig.JDBC_DRIVER_NAME);
		config = ConfigDescriptor.getInstance().getConfig();
		points = new ArrayList<>();
		mp = new HashMap<>();
		mySql = new MySqlLog();
		this.labID = labID;
	}

	@Override
	public void createSchema() throws SQLException {
		if (config.READ_FROM_FILE) {
			initSchema();
			mp.put("INT32", "PLAIN");
			mp.put("INT64", "PLAIN");
			mp.put("DOUBLE", "GORILLA");
			if (config.TAG_PATH) {
				if (config.STORE_MODE == 1) {
					for (Point p : points) {
						setStorgeGroup(p.getPath());
					}
				} else {
					Set<String> uniqueMeasurementName = new HashSet<>();
					for (Point p : points) {
						uniqueMeasurementName.add(p.measurement);
					}
					for (String measure : uniqueMeasurementName) {
						setStorgeGroup("root." + measure);
					}
				}

				for (Point p : points) {
					Set<String> uniqueFieldName = new HashSet<>();
					uniqueFieldName.addAll(p.fieldName);
					for (String sensor : uniqueFieldName) {
						createTimeseries(p.getPath(), sensor);
					}
				}
			} else {
				setStorgeGroup("root.device_0");
				Set<String> uniqueFieldName = new HashSet<>();
				for (Point p : points) {
					uniqueFieldName.addAll(p.fieldName);
				}
				for (String sensor : uniqueFieldName) {
					createTimeseries("root.device_0", sensor);
				}
			}
		} else {
			int groupSize = config.DEVICE_NUMBER / config.GROUP_NUMBER;
			ArrayList<String> group = new ArrayList<>();
			for (int i = 0; i < config.GROUP_NUMBER; i++) {
				group.add("group_" + i);
			}
			for (String g : group) {
				setStorgeGroup(g);
			}
			int count = 0;
			int groupIndex = 0;
			int timeseriesCount = 0;
			Statement statement = connection.createStatement();
			int timeseriesTotal = config.DEVICE_NUMBER * config.SENSOR_NUMBER;
			String path;
			for (String device : config.DEVICE_CODES) {
				if (count == groupSize) {
					groupIndex++;
					count = 0;
				}
				path = group.get(groupIndex) + "." + device;
				for (String sensor : config.SENSOR_CODES) {
					// createTimeseries(path, sensor);
					timeseriesCount++;
					createTimeseriesBatch(path, sensor, timeseriesCount, timeseriesTotal, statement);
				}
				count++;
			}
		}

	}

	private void initSchema() {
		// 解析到points
		BufferedReader reader = null;
		try {
			File file = new File(config.FILE_PATH);
			reader = new BufferedReader(new FileReader(file));
			String tempString = null;

			// 读到指定的行数或者文件结束
			while ((tempString = reader.readLine()) != null) {
				Point p = new Point();

				String[] pointInfo = tempString.split(" ");
				StringTokenizer st = new StringTokenizer(pointInfo[0], ",=");

				// 解析出measurement
				if (st.hasMoreElements())
					p.measurement = st.nextToken().replace('.', '_');

				// 解析出tag的K-V对
				while (st.hasMoreElements()) {
					p.tagName.add(st.nextToken().replace('.', '_'));
					p.tagValue.add(st.nextToken().replace('.', '_').replace('/', '_'));
				}

				// 解析出field的K-V对
				st = new StringTokenizer(pointInfo[1], ",=");
				while (st.hasMoreElements()) {
					p.fieldName.add(st.nextToken());
					p.fieldValue.add(string2num(st.nextToken()));
				}

				p.time = Long.parseLong(pointInfo[2]);
				if (points.size() == 0 || !points.get(0).getPath().equals(p.getPath())) {
					points.add(p);
				} else {
					break;
				}
			}

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (NumberFormatException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e1) {
				}
			}
		}
	}

	private static Number string2num(String str) {
		if (str.endsWith("i")) {
			return Long.parseLong(str.substring(0, str.length() - 1));
		} else {
			return Double.parseDouble(str);
		}
	}

	@Override
	public void insertOneBatchMulDevice(LinkedList<String> deviceCodes, int loopIndex, ThreadLocal<Long> totalTime,
			ThreadLocal<Long> errorCount) {
		Statement statement;
		int[] result;
		int errorNum = 0;
		try {
			statement = connection.createStatement();
			// 注意config.CACHE_NUM/(config.DEVICE_NUMBER/config.CLIENT_NUMBER)=整数,即批导入大小和客户端数的乘积可以被设备数整除
			for (int i = 0; i < (config.CACHE_NUM / deviceCodes.size()); i++) {
				for (String device : deviceCodes) {
					String sql = createSQLStatmentOfMulDevice(loopIndex, i, device);
					statement.addBatch(sql);
				}
			}
			long startTime = System.currentTimeMillis();
			result = statement.executeBatch();
			statement.clearBatch();
			statement.close();
			long endTime = System.currentTimeMillis();
			long costTime = endTime - startTime;
			for (int i = 0; i < result.length; i++) {
				if (result[i] == -1) {
					errorNum++;
				}
			}

			if (errorNum > 0) {
				LOGGER.info("Batch insert failed, the failed number is {}! ", errorNum);
			} else {
				LOGGER.info("{} execute {} loop, it costs {}s, totalTime {}s, throughput {} points/s",
						Thread.currentThread().getName(), loopIndex, costTime / 1000.0,
						(totalTime.get() + costTime) / 1000.0,
						(config.CACHE_NUM * config.SENSOR_NUMBER / (double) costTime) * 1000);
				totalTime.set(totalTime.get() + costTime);
				errorCount.set(errorCount.get() + errorNum);
			}
			mySql.saveInsertProcess(loopIndex, costTime / 1000.0, totalTime.get() / 1000.0, errorNum, config.REMARK);
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void insertOneBatch(String device, int loopIndex, ThreadLocal<Long> totalTime,
			ThreadLocal<Long> errorCount) {
		Statement statement;
		int[] result;
		int errorNum = 0;
		try {
			statement = connection.createStatement();
			for (int i = 0; i < config.CACHE_NUM; i++) {
				String sql = createSQLStatment(loopIndex, i, device);
				statement.addBatch(sql);
			}
			long startTime = System.currentTimeMillis();
			result = statement.executeBatch();
			statement.clearBatch();
			statement.close();
			long endTime = System.currentTimeMillis();
			long costTime = endTime - startTime;
			for (int i = 0; i < result.length; i++) {
				if (result[i] == -1) {
					errorNum++;
				}
			}
			if (errorNum > 0) {
				LOGGER.info("Batch insert failed, the failed number is {}! ", errorNum);
			} else {
				LOGGER.info("{} execute {} loop, it costs {}s, totalTime {}s, throughput {} points/s",
						Thread.currentThread().getName(), loopIndex, costTime / 1000.0,
						(totalTime.get() + costTime) / 1000.0,
						(config.CACHE_NUM * config.SENSOR_NUMBER / (double) costTime) * 1000);
				totalTime.set(totalTime.get() + costTime);
				errorCount.set(errorCount.get() + errorNum);
			}

			mySql.saveInsertProcess(loopIndex, (endTime - startTime) / 1000.0, totalTime.get() / 1000.0, errorNum,
					config.REMARK);

		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void insertOneBatch(LinkedList<String> cons, int batchIndex, ThreadLocal<Long> totalTime,
			ThreadLocal<Long> errorCount) throws SQLException {

		Statement statement;
		int[] result;
		int errorNum = 0;
		try {
			statement = connection.createStatement();
			for (String sql : cons) {
				statement.addBatch(sql);
			}

			long startTime = System.currentTimeMillis();
			result = statement.executeBatch();
			statement.clearBatch();
			statement.close();
			long endTime = System.currentTimeMillis();

			for (int i = 0; i < result.length; i++) {
				if (result[i] == -1) {
					errorNum++;
				}
			}

			if (errorNum > 0) {
				LOGGER.info("Batch insert failed, the failed number is {}! ", errorNum);
			} else {
				LOGGER.info("{} execute {} loop, it costs {}s, totalTime {}s, throughput {} items/s",
						Thread.currentThread().getName(), batchIndex, (endTime - startTime) / 1000.0,
						((totalTime.get() + (endTime - startTime)) / 1000.0),
						(cons.size() / (double) (endTime - startTime)) * 1000);
				totalTime.set(totalTime.get() + (endTime - startTime));
				errorCount.set(errorCount.get() + errorNum);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void executeOneQuery(List<Integer> devices, int index, long startTime, QueryClientThread client,
			ThreadLocal<Long> errorCount) {
		Statement statement=null;
		String sql = "";
		long startTimeStamp = 0, endTimeStamp = 0;
		try {
			statement = connection.createStatement();
			List<String> sensorList = new ArrayList<String>();

			switch (config.QUERY_CHOICE) {
			case 1:// 精确点查询
				long timeStamp = (startTime - Constants.START_TIMESTAMP) / config.POINT_STEP * config.POINT_STEP
						+ Constants.START_TIMESTAMP;
				if (config.IS_EMPTY_PRECISE_POINT_QUERY) {
					timeStamp += config.POINT_STEP / 2;
				}
				sql = createQuerySQLStatment(devices, config.QUERY_SENSOR_NUM, timeStamp, sensorList);
				break;
			case 2:// 模糊点查询（暂未实现）
				sql = createQuerySQLStatment(devices, config.QUERY_SENSOR_NUM, sensorList);
				break;
			case 3:// 聚合函数查询
				sql = createQuerySQLStatment(devices, config.QUERY_SENSOR_NUM, config.QUERY_AGGREGATE_FUN,startTime,
						startTime + config.QUERY_INTERVAL, sensorList);
				break;
			case 4:// 范围查询
				sql = createQuerySQLStatment(devices, config.QUERY_SENSOR_NUM, startTime,
						startTime + config.QUERY_INTERVAL, sensorList);
				break;
			case 5:// 条件查询
				sql = createQuerySQLStatment(devices, config.QUERY_SENSOR_NUM, startTime,
						startTime + config.QUERY_INTERVAL, config.QUERY_LOWER_LIMIT, sensorList);
				break;
			case 6:// 最近点查询
				sql = createQuerySQLStatment(devices, config.QUERY_SENSOR_NUM, "max_time", sensorList);
				break;
			case 7:// groupBy查询（暂时只有一个时间段）
				List<Long> startTimes = new ArrayList<Long>();
				List<Long> endTimes = new ArrayList<Long>();
				startTimes.add(startTime);
				endTimes.add(startTime+config.QUERY_GROUP_BY_SCOPE);
				sql = createQuerySQLStatment(devices, config.QUERY_AGGREGATE_FUN, config.QUERY_SENSOR_NUM,
						startTimes, endTimes, config.QUERY_LOWER_LIMIT,
						sensorList);
				break;
			}
			int line = 0;
			StringBuilder builder = new StringBuilder(sql);
			LOGGER.info("{} execute {} loop,提交执行的sql：{}",Thread.currentThread().getName(), index,builder.toString());
			startTimeStamp = System.currentTimeMillis();
			statement.execute(sql);
			ResultSet resultSet = statement.getResultSet();
			while (resultSet.next()) {
				line++;
//				int sensorNum = sensorList.size();
//				builder.append(" \ntimestamp = ").append(resultSet.getString(0)).append("; ");
//				for (int i = 1; i <= sensorNum; i++) {
//					builder.append(resultSet.getString(i)).append("; ");
//				}	
			}
			statement.close();
			endTimeStamp = System.currentTimeMillis();
//			LOGGER.info("{}",builder.toString());
			client.setTotalPoint(client.getTotalPoint() + line * config.QUERY_SENSOR_NUM * config.QUERY_DIVICE_NUM);
			client.setTotalTime(client.getTotalTime() + endTimeStamp - startTimeStamp);

			LOGGER.info(
					"{} execute {} loop, it costs {}s with {} result points cur_rate is {}points/s; "
							+ "TotalTime {}s with totalPoint {} rate is {}points/s",
					Thread.currentThread().getName(), index, (endTimeStamp - startTimeStamp) / 1000.0,
					line * config.QUERY_SENSOR_NUM * config.QUERY_DIVICE_NUM,
					line * config.QUERY_SENSOR_NUM * config.QUERY_DIVICE_NUM * 1000.0 / (endTimeStamp - startTimeStamp),
					(client.getTotalTime()) / 1000.0, client.getTotalPoint(),
					client.getTotalPoint() * 1000.0f / client.getTotalTime());
			mySql.saveQueryProcess(index, line * config.QUERY_SENSOR_NUM * config.QUERY_DIVICE_NUM,
					(endTimeStamp - startTimeStamp) / 1000.0f, config.REMARK);
		} catch (SQLException e) {
			errorCount.set(errorCount.get() + 1);
			LOGGER.error("{} execute query failed! Error：{}", Thread.currentThread().getName(), e.getMessage());
			LOGGER.error("执行失败的查询语句：{}", sql);
			mySql.saveQueryProcess(index, 0, (endTimeStamp - startTimeStamp) / 1000.0f, "query fail!"+sql);
			e.printStackTrace();
		}
		finally{
			try {
				if(statement!=null)
					statement.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	private void createTimeseries(String path, String sensor) {
		Statement statement;
		try {
			statement = connection.createStatement();
			if (config.READ_FROM_FILE) {
				String type = getTypeByField(sensor);
				statement.execute(String.format(createStatementFromFileSQL, path + "." + sensor, type, mp.get(type)));
			} else {
				statement.execute(String.format(createStatementSQL,
						Constants.ROOT_SERIES_NAME + "." + path + "." + sensor, "GORILLA"));
			}
			statement.close();
		} catch (SQLException e) {
			LOGGER.warn(e.getMessage());
		}

	}

	private void createTimeseriesBatch(String path, String sensor, int count, int timeseriesTotal,
			Statement statement) {
		try {
			statement.addBatch(String.format(createStatementSQL, Constants.ROOT_SERIES_NAME + "." + path + "." + sensor,
					config.ENCODING));
			if ((count % 1000) == 0) {
				long startTime = System.currentTimeMillis();
				statement.executeBatch();
				statement.clearBatch();
				long endTime = System.currentTimeMillis();
				LOGGER.info("batch create timeseries execute speed ,{},timeseries/s",
						1000000.0f / (endTime - startTime));
				if (count >= timeseriesTotal) {
					statement.close();
				}
				// statement.close();
			} else if (count >= timeseriesTotal) {
				statement.executeBatch();
				statement.clearBatch();
				statement.close();
			}
		} catch (SQLException e) {
			LOGGER.warn(e.getMessage());
		}
	}

	private void setStorgeGroup(String device) {
		Statement statement;
		try {
			statement = connection.createStatement();
			if (config.READ_FROM_FILE) {
				statement.execute(String.format(setStorageLevelSQL, device));
			} else {
				statement.execute(String.format(setStorageLevelSQL, Constants.ROOT_SERIES_NAME + "." + device));
			}
			statement.close();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void init() throws SQLException {
		connection = DriverManager.getConnection(String.format(Constants.URL, config.host, config.port), Constants.USER,
				Constants.PASSWD);
		mySql.initMysql(labID);
	}

	@Override
	public void close() throws SQLException {
		if (connection != null) {
			connection.close();
		}
		if (mySql != null) {
			mySql.closeMysql();
		}
	}

	private String createSQLStatment(int batch, int index, String device) {
		StringBuilder builder = new StringBuilder();
		String path = getGroupDevicePath(device);
		builder.append("insert into ").append(Constants.ROOT_SERIES_NAME).append(".").append(path).append("(timestamp");

		for (String sensor : config.SENSOR_CODES) {
			builder.append(",").append(sensor);
		}
		builder.append(") values(");
		long currentTime = Constants.START_TIMESTAMP + config.POINT_STEP * (batch * config.CACHE_NUM + index);
		builder.append(currentTime);
		for (String sensor : config.SENSOR_CODES) {
			FunctionParam param = config.SENSOR_FUNCTION.get(sensor);
			builder.append(",").append(Function.getValueByFuntionidAndParam(param, currentTime));
		}
		builder.append(")");
		return builder.toString();
	}

	private String createSQLStatmentOfMulDevice(int loopIndex, int i, String device) {
		StringBuilder builder = new StringBuilder();
		String path = getGroupDevicePath(device);
		builder.append("insert into ").append(Constants.ROOT_SERIES_NAME).append(".").append(path).append("(timestamp");

		for (String sensor : config.SENSOR_CODES) {
			builder.append(",").append(sensor);
		}
		builder.append(") values(");
		long currentTime = Constants.START_TIMESTAMP + config.POINT_STEP * (loopIndex * config.CACHE_NUM/(config.DEVICE_NUMBER/config.CLIENT_NUMBER) + i);
		builder.append(currentTime);
		for (String sensor : config.SENSOR_CODES) {
			FunctionParam param = config.SENSOR_FUNCTION.get(sensor);
			builder.append(",").append(Function.getValueByFuntionidAndParam(param, currentTime));
		}
		builder.append(")");
		return builder.toString();
	}

	/**
	 * 创建查询语句--(精确点查询)
	 * 
	 * @throws SQLException
	 */
	private String createQuerySQLStatment(List<Integer> devices, int num, long time, List<String> sensorList)
			throws SQLException {
		
		String strTime = sdf.format(new Date(time));
		StringBuilder builder = new StringBuilder(createQuerySQLStatment(devices, num, sensorList));
		builder.append(" WHERE time = ").append(strTime);
		return builder.toString();
	}

	/**
	 * 创建查询语句--(查询设备下的num个传感器数值)
	 * 
	 * @throws SQLException
	 */
	private String createQuerySQLStatment(List<Integer> devices, int num, List<String> sensorList) throws SQLException {
		StringBuilder builder = new StringBuilder();
		builder.append("SELECT ");
		if (num > config.SENSOR_NUMBER) {
			throw new SQLException("config.SENSOR_NUMBER is " + config.SENSOR_NUMBER
					+ " shouldn't less than the number of fields in querySql");
		}
		List<String> list = new ArrayList<String>();
		for (String sensor : config.SENSOR_CODES) {
			list.add(sensor);
		}
		Collections.shuffle(list);
		builder.append(list.get(0));
		sensorList.add(list.get(0));
		for (int i = 1; i < num; i++) {
			builder.append(" , ").append(list.get(i));
			sensorList.add(list.get(i));
		}
		builder.append(" FROM ").append(getFullGroupDevicePathByID(devices.get(0)));
		for (int i = 1; i < devices.size(); i++) {
			builder.append(" , ").append(getFullGroupDevicePathByID(devices.get(i)));
		}

		return builder.toString();
	}

	/** 创建查询语句--(带有聚合函数的查询) */
	private String createQuerySQLStatment(List<Integer> devices, int num, String method, List<String> sensorList) {
		StringBuilder builder = new StringBuilder();

		builder.append("SELECT ");

		List<String> list = new ArrayList<String>();
		for (String sensor : config.SENSOR_CODES) {
			list.add(sensor);
		}
		Collections.shuffle(list);
		if(method.length()>2) {
			builder.append(method).append("(").append(list.get(0)).append(")");
			sensorList.add(list.get(0));
			for (int i = 1; i < num; i++) {
				builder.append(" , ").append(method).append("(").append(list.get(i)).append(")");
				sensorList.add(list.get(i));
			}
		}
		else {
			builder.append(list.get(0));
			sensorList.add(list.get(0));
			for (int i = 1; i < num; i++) {
				builder.append(" , ").append(list.get(i));
				sensorList.add(list.get(i));
			}
		}
		

		builder.append(" FROM ").append(getFullGroupDevicePathByID(devices.get(0)));
		for (int i = 1; i < devices.size(); i++) {
			builder.append(" , ").append(getFullGroupDevicePathByID(devices.get(i)));
		}
		return builder.toString();
	}
	
	/** 创建查询语句--(带有聚合函数以及时间约束的查询) */
	private String createQuerySQLStatment(List<Integer> devices, int num, String method, long startTime, 
			long endTime, List<String> sensorList) {
		StringBuilder builder = new StringBuilder(createQuerySQLStatment(devices, num, method, sensorList));
		String strstartTime = sdf.format(new Date(startTime));
		String strendTime = sdf.format(new Date(endTime));
		builder.append(" WHERE time > ");
		builder.append(strstartTime).append(" AND time < ").append(strendTime);
		return builder.toString();
	}

	/**
	 * 创建查询语句--(带有时间约束条件的查询)
	 * 
	 * @throws SQLException
	 */
	private String createQuerySQLStatment(List<Integer> devices, int num, long startTime, long endTime,
			List<String> sensorList) throws SQLException {
		
		String strstartTime = sdf.format(new Date(startTime));
		String strendTime = sdf.format(new Date(endTime));
		StringBuilder builder = new StringBuilder();
		builder.append(createQuerySQLStatment(devices, num, sensorList)).append(" WHERE time > ");
		builder.append(strstartTime).append(" AND time < ").append(strendTime);
		return builder.toString();
	}

	/**
	 * 创建查询语句--(带有时间约束以及条件约束的查询)
	 * 
	 * @throws SQLException
	 */
	private String createQuerySQLStatment(List<Integer> devices, int num, long startTime, long endTime, Number value,
			List<String> sensorList) throws SQLException {
		StringBuilder builder = new StringBuilder();
		builder.append(createQuerySQLStatment(devices, num, startTime, endTime, sensorList));
		
		for (int id : devices) {
			String prefix = getFullGroupDevicePathByID(id);
			for (int i = 0; i < sensorList.size(); i++) {
				builder.append(" AND ").append(prefix).append(".").append(sensorList.get(i)).append(" > ")
				.append(value);
				
			}
		}
		
		return builder.toString();
	}
	
	/**
	 * 创建查询语句--(带有时间约束以及条件约束的GroupBy查询)
	 * 
	 * @throws SQLException
	 */
	private String createQuerySQLStatment(List<Integer> devices, String method, int num, List<Long> startTime, List<Long> endTime, Number value,
			List<String> sensorList) throws SQLException {
		StringBuilder builder = new StringBuilder();
		builder.append(createQuerySQLStatment(devices,num,method, sensorList));
		builder.append(" WHERE ");
		for (int id : devices) {
			String prefix = getFullGroupDevicePathByID(id);
			for (int i = 0; i < sensorList.size(); i++) {
				builder.append(prefix).append(".").append(sensorList.get(i)).append(" > ")
				.append(value).append(" AND ");
			}
		}
		builder.delete(builder.lastIndexOf("AND"), builder.length());
		builder.append(" GROUP BY(").append(config.QUERY_INTERVAL).append("ms, ").append(Constants.START_TIMESTAMP);
		for(int i = 0;i<startTime.size();i++) {
			String strstartTime = sdf.format(new Date(startTime.get(i)));
			String strendTime = sdf.format(new Date(endTime.get(i)));
			builder.append(",[").append(strstartTime).append(",").append(strendTime).append("]");
		}
		builder.append(")");
		return builder.toString();
	}

	/**
	 * 返回数据库中设备的时间跨度
	 * 
	 * @throws SQLException
	 */
	@Override
	public long getTotalTimeInterval() throws SQLException {
		long startTime = Constants.START_TIMESTAMP, endTime = Constants.START_TIMESTAMP;
		String sql = "select MAX_TIME( s_0 ) from " + Constants.ROOT_SERIES_NAME + ".group_0.d_0";
		Statement statement = connection.createStatement();
		statement.execute(sql);
		ResultSet resultSet = statement.getResultSet();
		if (resultSet.next()) {
			endTime = resultSet.getLong(1);
		}
		statement.close();

		sql = "select MIN_TIME( s_0 ) from " + Constants.ROOT_SERIES_NAME + ".group_0.d_0";
		statement = connection.createStatement();
		statement.execute(sql);
		resultSet = statement.getResultSet();
		if (resultSet.next()) {
			startTime = resultSet.getLong(1);
		}
		statement.close();

		return endTime - startTime;
	}

	private String getGroupDevicePath(String device) {
		String[] spl = device.split("_");
		int deviceIndex = Integer.parseInt(spl[1]);
		int groupSize = config.DEVICE_NUMBER / config.GROUP_NUMBER;
		int groupIndex = deviceIndex / groupSize;
		return "group_" + groupIndex + "." + device;
	}

	private String getFullGroupDevicePathByID(int id) {
		int groupSize = config.DEVICE_NUMBER / config.GROUP_NUMBER;
		int groupIndex = id / groupSize;
		return Constants.ROOT_SERIES_NAME + ".group_" + groupIndex + "." + config.DEVICE_CODES.get(id);
	}

	private String getTypeByField(String name) {
		if (name.endsWith("_percent") || name.startsWith("usage_")) {
			return "DOUBLE";
		}
		return "INT64";
	}

	/**
	 * 强制IoTDB数据库将数据写入磁盘
	 * 
	 * @throws SQLException
	 */
	@Override
	public void flush() {
		String sql = "flush";
		try {
			Statement statement = connection.createStatement();
			statement.execute(sql);
			statement.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	@Override
	public long count(String group,String device,String sensor){

		return 0;
	}
}
