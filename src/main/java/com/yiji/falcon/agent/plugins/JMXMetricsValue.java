package com.yiji.falcon.agent.plugins;/**
 * Copyright 2014-2015 the original ql
 * Created by QianLong on 16/5/3.
 */

import com.yiji.falcon.agent.config.AgentConfiguration;
import com.yiji.falcon.agent.falcon.CounterType;
import com.yiji.falcon.agent.falcon.FalconReportObject;
import com.yiji.falcon.agent.falcon.MetricsType;
import com.yiji.falcon.agent.jmx.vo.JMXMetricsValueInfo;
import com.yiji.falcon.agent.jmx.vo.JMXObjectNameInfo;
import com.yiji.falcon.agent.util.CustomerMath;
import com.yiji.falcon.agent.util.StringUtils;
import com.yiji.falcon.agent.vo.JMXMetricsConfiguration;
import org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.openmbean.CompositeDataSupport;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.management.MemoryUsage;
import java.util.*;

/**
 * 从JMX获取监控值抽象类
 * Created by QianLong on 16/5/3.
 */
public abstract class JMXMetricsValue extends MetricsCommon{
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    protected List<JMXMetricsValueInfo> metricsValueInfos;

    public JMXMetricsValue() {
        this.metricsValueInfos = getMetricsValueInfos();
    }

    /**
     * 获取配置文件配置的监控值
     * @return
     */
    private Set<JMXMetricsConfiguration> getMetricsConfig() throws IOException {
        Set<JMXMetricsConfiguration> jmxMetricsConfigurations = new HashSet<>();

        setMetricsConfig("agent.common.metrics.type.",AgentConfiguration.INSTANCE.getJmxCommonMetricsConfPath(),jmxMetricsConfigurations);
        setMetricsConfig(getBasePropertiesKey(),getMetricsConfPath(),jmxMetricsConfigurations);

        return jmxMetricsConfigurations;
    }

    /**
     * 构建监控值报告的中间对象
     */
    private class KitObjectNameMetrics {
        JMXObjectNameInfo jmxObjectNameInfo;
        JMXMetricsConfiguration jmxMetricsConfiguration;
    }

    /**
     * 获取监控值报告的中间对象的辅助方法
     * @param jmxObjectNameInfos
     * @param metricsConfiguration
     * @return
     */
    private Set<KitObjectNameMetrics> getKitObjectNameMetrics(Collection<JMXObjectNameInfo> jmxObjectNameInfos, JMXMetricsConfiguration metricsConfiguration){
        Set<KitObjectNameMetrics> kitObjectNameMetricsSet = new HashSet<>();
        for (JMXObjectNameInfo jmxObjectNameInfo : jmxObjectNameInfos) {
            if(jmxObjectNameInfo.getObjectName().toString().contains(metricsConfiguration.getObjectName())){
                KitObjectNameMetrics kitObjectNameMetrics = new KitObjectNameMetrics();
                kitObjectNameMetrics.jmxObjectNameInfo = jmxObjectNameInfo;
                kitObjectNameMetrics.jmxMetricsConfiguration = metricsConfiguration;
                kitObjectNameMetricsSet.add(kitObjectNameMetrics);
            }
        }
        return kitObjectNameMetricsSet;
    }

    /**
     * 生成监控报告的辅助方法
     * @param kitObjectNameMetricses
     * @param metricsValueInfo
     * @return
     */
    private Set<FalconReportObject> generatorReportObject(Collection<KitObjectNameMetrics> kitObjectNameMetricses,JMXMetricsValueInfo metricsValueInfo){
        Set<FalconReportObject> result = new HashSet<>();

        //用于判断监控值是否重复添加,若出现重复添加,进行监控值比较
        Map<String,FalconReportObject> repeat = new HashMap<>();

        for (KitObjectNameMetrics kitObjectNameMetrics : kitObjectNameMetricses) {
            JMXObjectNameInfo jmxObjectNameInfo = kitObjectNameMetrics.jmxObjectNameInfo;
            JMXMetricsConfiguration jmxMetricsConfiguration = kitObjectNameMetrics.jmxMetricsConfiguration;
            String metricsValue = jmxObjectNameInfo.getMetricsValue().get(jmxMetricsConfiguration.getMetrics());
            if(metricsValue != null){
                //服务的标识后缀名
                String name = metricsValueInfo.getJmxConnectionInfo().getName();

                FalconReportObject requestObject = new FalconReportObject();
                setReportCommonValue(requestObject);
                requestObject.setMetric(getMetricsName(jmxMetricsConfiguration.getAlias()));//设置push obj 的 metrics
                try {
                    //设置push obj 的 Counter
                    requestObject.setCounterType(CounterType.valueOf(jmxMetricsConfiguration.getCounterType()));
                } catch (IllegalArgumentException e) {
                    log.error("错误的{} counterType配置:{},只能是 {} 或 {},未修正前,将忽略此监控值",jmxMetricsConfiguration.getAlias(),jmxMetricsConfiguration.getCounterType(),CounterType.COUNTER,CounterType.GAUGE,e);
                    continue;
                }
                requestObject.setTimestamp(System.currentTimeMillis() / 1000);
                requestObject.setObjectName(jmxObjectNameInfo.getObjectName());
                Object newValue = executeJsExpress(kitObjectNameMetrics.jmxMetricsConfiguration.getValueExpress(),metricsValue);
                if(NumberUtils.isNumber(String.valueOf(newValue))){
                    requestObject.setValue(String.valueOf(newValue));
                }else{
                    log.error("异常:监控指标值{} - {} : {}不能转换为数字,将忽略此监控值",jmxMetricsConfiguration.getObjectName(),jmxMetricsConfiguration.getMetrics(),metricsValue);
                    continue;
                }

                requestObject.appendTags(getTags(name, MetricsType.JMXOBJECTCONF)).appendTags(jmxMetricsConfiguration.getTag());

                //监控值重复性判断
                FalconReportObject reportInRepeat = repeat.get(jmxMetricsConfiguration.getMetrics());
                if(reportInRepeat == null){
                    //第一次添加
                    result.add(requestObject);
                    repeat.put(jmxMetricsConfiguration.getMetrics(),requestObject);
                }else{
                    if(!reportInRepeat.equals(requestObject)){
                        // 若已有记录而且不相同,进行区分保存
                        result.remove(reportInRepeat);
                        reportInRepeat.appendTags(requestObject.getObjectName().toString());
                        result.add(reportInRepeat);

                        requestObject.appendTags(requestObject.getObjectName().toString());
                        if(!result.contains(requestObject)){
                            result.add(requestObject);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 获取所有的监控值报告
     * @return
     * @throws IOException
     */
    public Collection<FalconReportObject> getReportObjects() throws IOException {
        Set<FalconReportObject> result = new HashSet<>();

        if(metricsValueInfos == null || metricsValueInfos.isEmpty()){
            //当第一次启动agent时,当前服务未启动
            //获取不到监控值,返回所有zk不可用的监控报告
            log.error(getType() + " JMX 连接获取失败");
            result.add(generatorVariabilityReport(false,"allUnVariability"));
            return result;
        }
        for (JMXMetricsValueInfo metricsValueInfo : metricsValueInfos) {

            if(!metricsValueInfo.getJmxConnectionInfo().isValid()){
                //该连接不可用,添加该 jmx不可用的监控报告
                result.add(generatorVariabilityReport(false,metricsValueInfo.getJmxConnectionInfo().getName()));
            }else{

                Set<KitObjectNameMetrics> kitObjectNameMetricsSet = new HashSet<>();

                for (JMXMetricsConfiguration metricsConfiguration : getMetricsConfig()) {// 配置文件配置的需要监控的
                    kitObjectNameMetricsSet.addAll(getKitObjectNameMetrics(metricsValueInfo.getJmxObjectNameInfoList(),metricsConfiguration));
                }

                result.addAll(generatorReportObject(kitObjectNameMetricsSet,metricsValueInfo));

                //添加可用性报告
                result.add(generatorVariabilityReport(true,metricsValueInfo.getJmxConnectionInfo().getName()));

                //添加內建报告
                result.addAll(getInbuiltReportObjects(metricsValueInfo));
                Collection<FalconReportObject> inbuilt = getInbuiltReportObjectsForValid(metricsValueInfo);
                if(inbuilt != null && !inbuilt.isEmpty()){
                    result.addAll(inbuilt);
                }
            }

        }

        return result;
    }

    /**
     * 內建监控报告
     * HeapMemoryCommitted
     * NonHeapMemoryCommitted
     * HeapMemoryFree
     * NonHeapMemoryFree
     * HeapMemoryMax
     * NonHeapMemoryMax
     * HeapMemoryUsed
     * NonHeapMemoryUsed
     *
     * @return
     */
    private Collection<FalconReportObject> getInbuiltReportObjects(JMXMetricsValueInfo metricsValueInfo) {
        List<FalconReportObject> result = new ArrayList<>();
        if(metricsValueInfo == null || !metricsValueInfo.getJmxConnectionInfo().isValid()){
            return result;
        }
        try {
            for (JMXObjectNameInfo objectNameInfo : metricsValueInfo.getJmxObjectNameInfoList()) {
                if("java.lang:type=Memory".equals(objectNameInfo.getObjectName().toString())){
                    //服务的标识后缀名
                    String name = objectNameInfo.getJmxConnectionInfo().getName();

                    MemoryUsage heapMemoryUsage =  MemoryUsage.from((CompositeDataSupport)objectNameInfo.
                            getJmxConnectionInfo().getmBeanServerConnection().getAttribute(objectNameInfo.getObjectName(), "HeapMemoryUsage"));
                    MemoryUsage nonHeapMemoryUsage =  MemoryUsage.from((CompositeDataSupport)objectNameInfo.
                            getJmxConnectionInfo().getmBeanServerConnection().getAttribute(objectNameInfo.getObjectName(), "NonHeapMemoryUsage"));
                    FalconReportObject falconReportObject = new FalconReportObject();
                    setReportCommonValue(falconReportObject);
                    falconReportObject.setCounterType(CounterType.GAUGE);
                    falconReportObject.setTimestamp(System.currentTimeMillis() / 1000);
                    falconReportObject.setObjectName(objectNameInfo.getObjectName());

                    falconReportObject.appendTags(getTags(name,MetricsType.JMXOBJECTBUILDIN));

                    falconReportObject.setMetric(getMetricsName("HeapMemoryCommitted"));
                    falconReportObject.setValue(String.valueOf(heapMemoryUsage.getCommitted()));
                    result.add(falconReportObject);
                    falconReportObject.setMetric(getMetricsName("NonHeapMemoryCommitted"));
                    falconReportObject.setValue(String.valueOf(nonHeapMemoryUsage.getCommitted()));
                    result.add(falconReportObject);

                    falconReportObject.setMetric(getMetricsName("HeapMemoryFree"));
                    falconReportObject.setValue(String.valueOf(heapMemoryUsage.getMax() - heapMemoryUsage.getUsed()));
                    result.add(falconReportObject);

                    falconReportObject.setMetric(getMetricsName("HeapMemoryMax"));
                    falconReportObject.setValue(String.valueOf(heapMemoryUsage.getMax()));
                    result.add(falconReportObject);

                    falconReportObject.setMetric(getMetricsName("HeapMemoryUsed"));
                    falconReportObject.setValue(String.valueOf(heapMemoryUsage.getUsed()));
                    result.add(falconReportObject);
                    falconReportObject.setMetric(getMetricsName("NonHeapMemoryUsed"));
                    falconReportObject.setValue(String.valueOf(nonHeapMemoryUsage.getUsed()));
                    result.add(falconReportObject);

                    //堆内存使用比例
                    falconReportObject.setMetric(getMetricsName("HeapMemoryUsedRatio"));
                    falconReportObject.setValue(String.valueOf(CustomerMath.div(heapMemoryUsage.getUsed(),heapMemoryUsage.getMax(),2) * 100));
                    result.add(falconReportObject);

                }
            }

        } catch (Exception e) {
            log.error("获取jmx 内置监控数据异常",e);
        }
        return result;
    }

    /**
     * 设置报告对象公共的属性
     * endpoint
     * step
     * @param falconReportObject
     */
    @Override
    public void setReportCommonValue(FalconReportObject falconReportObject){
        if(falconReportObject != null){
            falconReportObject.setEndpoint(getEndpointByTrans(AgentConfiguration.INSTANCE.getAgentEndpoint()));
            falconReportObject.setStep(getStep());
        }
    }

    /**
     * 设置配置的jmx监控属性
     * @param basePropertiesKey
     * 配置属性的前缀key值
     * @param propertiesPath
     * 监控属性的配置文件路径
     * @param jmxMetricsConfigurations
     * 需要保存的集合对象
     * @throws IOException
     */
    private void setMetricsConfig(String basePropertiesKey,String propertiesPath,Set<JMXMetricsConfiguration> jmxMetricsConfigurations) throws IOException {

        if(!StringUtils.isEmpty(basePropertiesKey) &&
                !StringUtils.isEmpty(propertiesPath)){
            Properties properties = new Properties();
            properties.load(new FileInputStream(propertiesPath));
            for (int i = 1; i <= 100; i++) {
                String objectName = basePropertiesKey + i +".objectName";
                if(!StringUtils.isEmpty(properties.getProperty(objectName))){
                    JMXMetricsConfiguration metricsConfiguration = new JMXMetricsConfiguration();
                    metricsConfiguration.setObjectName(properties.getProperty(objectName));//设置ObjectName
                    metricsConfiguration.setCounterType(properties.getProperty(basePropertiesKey + i + ".counterType"));//设置counterType
                    metricsConfiguration.setMetrics(properties.getProperty(basePropertiesKey + i + ".metrics"));//设置metrics
                    metricsConfiguration.setValueExpress(properties.getProperty(basePropertiesKey + i + ".valueExpress"));//设置metrics
                    String tag = properties.getProperty(basePropertiesKey + i + ".tag");
                    metricsConfiguration.setTag(StringUtils.isEmpty(tag) ? "" : tag);//设置tag
                    String alias = properties.getProperty(basePropertiesKey + i + ".alias");
                    metricsConfiguration.setAlias(StringUtils.isEmpty(alias) ? metricsConfiguration.getMetrics() : alias);

                    jmxMetricsConfigurations.add(metricsConfiguration);
                }
            }
        }
    }

    /**
     * 获取所有的具体服务的JMX监控值VO
     * @return
     */
    protected abstract List<JMXMetricsValueInfo> getMetricsValueInfos();

    /**
     * 当可用时的內建监控报告
     * 此方法只有在监控对象可用时,才会调用,并加入到所有的监控值报告中(getReportObjects)
     * @param metricsValueInfo
     * 当前的JMXMetricsValueInfo信息
     * @return
     */
    protected abstract Collection<FalconReportObject> getInbuiltReportObjectsForValid(JMXMetricsValueInfo metricsValueInfo);

    /**
     * 获取step
     * @return
     */
    public abstract int getStep();

    /**
     * 自定义的监控属性的监控值基础配置名
     * @return
     */
    public abstract String getBasePropertiesKey();

    /**
     * 自定义的监控属性的配置文件位置
     * @return
     */
    public abstract String getMetricsConfPath();

    /**
     * JMX连接的服务名
     * @return
     */
    public abstract String getServerName();
}