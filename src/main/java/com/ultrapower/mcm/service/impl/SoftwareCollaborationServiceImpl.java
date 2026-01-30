package com.ultrapower.mcm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.shaded.com.google.gson.Gson;
import com.alibaba.nacos.shaded.com.google.gson.GsonBuilder;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ultrapower.mcm.annotate.YamlPath;
import com.ultrapower.mcm.config.BaseDirectoryProperties;
import com.ultrapower.mcm.config.SoftwareDeployConfig;
import com.ultrapower.mcm.config.SoftwarePipelineConfig;
import com.ultrapower.mcm.dto.request.QueryDeploySoftwareInfoDto;
import com.ultrapower.mcm.dto.request.SoftwareProductSelectRequestDto;
import com.ultrapower.mcm.dto.response.*;
import com.ultrapower.mcm.entity.*;
import com.ultrapower.mcm.mapper.*;
import com.ultrapower.mcm.service.*;
import com.ultrapower.mcm.util.HttpClientApiCaller;
import com.ultrapower.mcm.util.KubernetesConfigEditor;
import com.ultrapower.mcm.util.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * * Created with IntelliJ IDEA.
 * * @Author: fsp
 * * @Date: 2025/5/28
 * * @Time: 11:34
 * * @Desc: 软件协同  实现类
 * * To change this template use File | Settings | File Templates.
 */
@Service
@Slf4j
public class SoftwareCollaborationServiceImpl implements ISoftwareCollaborationService {

    @Autowired
    private SoftwareInstallRecordMapper softwareInstallRecordMapper;

    @Autowired
    private SoftwareDeploySoftwareInfoMapper softwareDeploySoftwareInfoMapper;

    @Autowired
    private SoftwareDeploySoftwareAppInfoMapper softwareDeploySoftwareAppInfoMapper;

    @Autowired
    private SoftwareDeployEnvInfoMapper softwareDeployEnvInfoMapper;

    @Autowired
    private SoftwareDeployPortsInfoMapper softwareDeployPortsInfoMapper;

    @Autowired
    private ISoftWarDeployDownLoadService softWarDeployDownLoadService;

    //软件目录
    @Autowired
    private SoftwareCatalogMapper softwareCatalogMapper;

    @Autowired
    private SoftwareDeployConfig softwareDeployConfig;

    @Autowired
    private ISoftWarDeployDecompressionPackageService softWarDeployDecompressionPackageService;

    @Autowired
    private SoftwareDeployInstallHandler softwareDeployInstallHandler;

    @Autowired
    private BaseDirectoryProperties baseDirectoryProperties;

    @Autowired
    private ISoftwareAppMakeService softwareAppMakeService;

    @Autowired
    private ISoftwareAppInstallService softwareAppInstallService;

    @Autowired
    private ISoftwareConfigMapDeployService configMapDeployService;

    @Value("${app.dz.http.username:admin@unitechs.com}")
    private String dzUserName;

    @Value("${app.dz.http.password:Abcd@1234}")
    private String dzPW;

    @Value("${service.center.name}")
    private String centerName;

    @Value("${app.deploy.cluster-id:371abfe39a09b184b1237d229be67a32}")
    private String defaultClusterId;

    /**
     * 需要更新多个YAML路径的字段配置
     * Key: 字段名, Value: 需要更新的YAML路径列表
     */
    private static final Map<String, List<String>> MULTI_PATH_FIELDS = new HashMap<>();

    static {
        // 预留多路径字段配置（clusterId 已改为文本替换方式处理）
    }

    /**
     * 用于更新 YAML 中 clusterId 行的正则
     * 保持原有缩进与引号格式
     */
    private static final Pattern CLUSTER_ID_LINE_PATTERN = Pattern.compile(
            "(?m)^(\\s*k8s\\.unitechs\\.com/clusterId\\s*:\\s*)([\"']?)([^\"'\\r\\n]*)([\"']?)\\s*$");

    @Override
    public PageInfo<SoftwareInstallRecord> selectRecordPage(SoftInstallRecordQueryDto recordQueryDto) {
        log.info("软件安装记录查询，参数: {}", recordQueryDto);
        PageHelper.startPage(recordQueryDto.getPageNo(), recordQueryDto.getPageSize());
        List<SoftwareInstallRecord> installRecordList = softwareInstallRecordMapper.selectByQuery(recordQueryDto);
        if (CollUtil.isNotEmpty(installRecordList)) {
            return new PageInfo<>(installRecordList);
        }
        return new PageInfo<>(Lists.newArrayList());
    }

    /**
     * 这里原来设计为三层关联，现需求确认目录只能单选！所以只需要两层结构
     *
     * @param softwareProductSelectRequestList
     * @return
     */
    @Override
    public ApiResponse<String> createSoftWareProduct(List<SoftwareProductSelectRequestDto> softwareProductSelectRequestList) {
        log.debug("软件部署_软件制品选择，参数: {}", JSON.toJSONString(softwareProductSelectRequestList));
        //判断参数长度，目前需求为单选，所以参数长度为1
        if (softwareProductSelectRequestList.size() != 1) {
            return new ApiResponse<>("500", "软件目录只能单选", null);
        }
        //随机长整型
//        SoftwareDeployInfo softwareDeployInfo = new SoftwareDeployInfo();
//        softwareDeployInfo.setDeployStep("软件制品");
//        softwareDeployInfo.setCreateTime(LocalDateTime.now());
//        softwareDeployInfoMapper.insert(softwareDeployInfo);
//        log.info("软件部署_软件制品保存成功，ID: {}", softwareDeployInfo.getId());

        SnowflakeIdGenerator snowflakeIdGenerator = new SnowflakeIdGenerator(1, 1);
        SoftwareDeploySoftwareInfo softwareDeploySoftwareInfo;
        List<SoftwareDeploySoftwareInfo> softwareDeploySoftwareInfos = new ArrayList<>(softwareProductSelectRequestList.size());
        List<SoftwareDeploySoftwareAppInfo> deploySoftwareAppInfoAlls = new ArrayList<>();
        final Long deployId = snowflakeIdGenerator.nextId();
        //遍历赋值并保存
        for (SoftwareProductSelectRequestDto productSelectRequestDto : softwareProductSelectRequestList) {
            softwareDeploySoftwareInfo = new SoftwareDeploySoftwareInfo();
            softwareDeploySoftwareInfo.setId(deployId);
            softwareDeploySoftwareInfo.setDeployInfoId(deployId);
            softwareDeploySoftwareInfo.setCenterName(productSelectRequestDto.getCenterName());
            softwareDeploySoftwareInfo.setCenterCode(productSelectRequestDto.getCenterCode());
            softwareDeploySoftwareInfo.setSoftwareCode(productSelectRequestDto.getSoftwareCode());
            softwareDeploySoftwareInfo.setSoftwareName(productSelectRequestDto.getSoftwareName());
            softwareDeploySoftwareInfo.setSoftwareVersion(productSelectRequestDto.getSoftwareVersion());
            softwareDeploySoftwareInfo.setInstallPackagePath(productSelectRequestDto.getInstallPackagePath());
            softwareDeploySoftwareInfo.setBelongSystem(productSelectRequestDto.getBelongSystem());
            softwareDeploySoftwareInfo.setSoftwareDesc(productSelectRequestDto.getSoftwareDesc());
            softwareDeploySoftwareInfo.setServiceList(productSelectRequestDto.getServiceList());
            softwareDeploySoftwareInfo.setSoftwareInstallPath(productSelectRequestDto.getSoftwareInstallPath());
            softwareDeploySoftwareInfo.setSoftwareInstallVersion(productSelectRequestDto.getSoftwareInstallVersion());
            softwareDeploySoftwareInfo.setSoftwareInstallDate(productSelectRequestDto.getSoftwareInstallDate());
            softwareDeploySoftwareInfo.setServiceNum(productSelectRequestDto.getServiceNum());
            softwareDeploySoftwareInfo.setDeployStep("软件制品");
            softwareDeploySoftwareInfos.add(softwareDeploySoftwareInfo);

            //软件目录引用的软件信息对象,保存关联关系
            List<SoftwareDeploySoftwareAppInfo> deploySoftwareAppInfos = productSelectRequestDto.getSoftwareDefinitionRels().stream().map(softwareDefinition -> {
                SoftwareDeploySoftwareAppInfo softwareDeploySoftwareAppInfo = new SoftwareDeploySoftwareAppInfo();
                softwareDeploySoftwareAppInfo.setId(snowflakeIdGenerator.nextId());
                softwareDeploySoftwareAppInfo.setDeploySoftwareInfoId(deployId);
                softwareDeploySoftwareAppInfo.setSvcCode(softwareDefinition.getSvcCode());
                softwareDeploySoftwareAppInfo.setSvcName(softwareDefinition.getSvcName());
                softwareDeploySoftwareAppInfo.setSysCode(softwareDefinition.getSysCode());
                softwareDeploySoftwareAppInfo.setSysName(softwareDefinition.getSysName());
                return softwareDeploySoftwareAppInfo;
            }).collect(Collectors.toList());
            deploySoftwareAppInfoAlls.addAll(deploySoftwareAppInfos);
        }
        softwareDeploySoftwareInfoMapper.batchInsert(softwareDeploySoftwareInfos);
        softwareDeploySoftwareAppInfoMapper.batchInsert(deploySoftwareAppInfoAlls);

        //不能在这个方法里下载，太耗时了，提供的是一个一个的下载，假定已下载到指定目录（找地方下载，并解析好，根据软件名称，中心编码查库？？）
        try {
            //下载文件 最终目录buildPackSavePath = buildPackSavePath +File.separator+ systemCode+File.separator+deployId;
            //softwareCode	软件编码	字符串	20字符	非空
            //centerCode	协同中心编码	字符串	200字符	非空
            //softwareVersion	软件版本	字符串	20字符	非空
            SoftwareProductSelectRequestDto softwareProductSelectRequestDto = softwareProductSelectRequestList.get(0);
            Map<String, String> requestParams = new HashMap<>();
            requestParams.put("softwareCode", softwareProductSelectRequestDto.getSoftwareCode());
            requestParams.put("centerCode", softwareProductSelectRequestDto.getCenterCode());
            requestParams.put("softwareVersion", softwareProductSelectRequestDto.getSoftwareInstallVersion());
//            requestParams.put("softwareName", softwareProductSelectRequestDto.getSoftwareName());
//            requestParams.put("centerName", softwareProductSelectRequestDto.getCenterName());

            //请求头
            Map<String, String> headers = new HashMap<>();
            String auth = dzUserName + ":" + dzPW;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            String authHeader = "Basic " + encodedAuth;
            headers.put(HttpHeaders.AUTHORIZATION, authHeader);

            //正式走接口调用，构建包下载
            String buildPackDownUrl = SoftwarePipelineConfig.SoftwareDeployInstallUrlEnum.SOFTWARE_BUILD_PACKAGE_DOWNLOAD_URL.getUrl();
            BaseDirectoryProperties.DirectoryConfig software = baseDirectoryProperties.getServices().get("software");
            String realBuildPackDownUrl = String.format(buildPackDownUrl, software.getUrl() + ":" + software.getPort());
            File file = softWarDeployDownLoadService.downloadZip(deployId, softwareProductSelectRequestDto.getSoftwareCode(),
                    realBuildPackDownUrl, null, headers, JSON.toJSONString(requestParams),
                    softwareProductSelectRequestDto.getSoftwareCode() + "@" + softwareProductSelectRequestDto.getSoftwareInstallVersion() + ".zip");
            //模拟下载后的压缩文件，用于测试
//            File file = new File(softwareDeployConfig.getBuildPackSavePath());
//            File file = new File("D:\\work\\项目文档\\2025\\CBG-XW多中心协同\\真实的构建包\\hyy-test.zip");
            if (Objects.nonNull(file)) {
                log.info("下载构建包成功,路径：{}，文件大小：{}，开始解压文件", file.getAbsolutePath(), Files.size(file.toPath()));
                //解压文件
                Map<String, List<File>> unzipFilesMap = softWarDeployDecompressionPackageService.deployDecompressionPackage(file, file.toPath().getParent());
                log.info("解压文件成功,解压文件个数：{}", unzipFilesMap.size());
                //遍历解压文件,找到deploy文件，并解析其中的端口信息，环境变量，服务信息等
                parseDeployFile(snowflakeIdGenerator, deploySoftwareAppInfoAlls, unzipFilesMap);
            } else {
                log.error("下载构建包失败，下载构建为空文件");
            }
        } catch (IOException e) {
            log.error("解析deploy文件异常:{}", e.getMessage());
        }
        return new ApiResponse<>("200", "保存成功", String.valueOf(deployId));
    }

    private void parseDeployFile(SnowflakeIdGenerator snowflakeIdGenerator, List<SoftwareDeploySoftwareAppInfo> deploySoftwareAppInfoAlls, Map<String, List<File>> unzipFilesMap) throws IOException {
        Yaml yaml = new Yaml();
        // 入库对象信息
        List<SoftwareDeployEnvInfo> softwareDeployEnvInfoList = new ArrayList<>();
        List<SoftwareDeployPortsInfo> softwareDeployPortsInfoList = new ArrayList<>();
        //遍历部署的具体软件名称，找到对应的deployment文件，解析其中的环境变量和端口信息与其他问题
        for (SoftwareDeploySoftwareAppInfo deploySoftwareAppInfo : deploySoftwareAppInfoAlls) {
            Long id = deploySoftwareAppInfo.getId();
            //软件编码，根据svcCode查询同名文件夹下的deploy文件
            String svcCode = deploySoftwareAppInfo.getSvcCode();
            for (Map.Entry<String, List<File>> entry : unzipFilesMap.entrySet()) {
                String key = entry.getKey();
                log.info("遍历deployFile文件:{},查询软件code:{}对应的文件", key, svcCode);
                //具体实现在这里匹配，文件路劲及名称包含系统code,
                if (key.contains(svcCode) && isDeploymentFile(key)) {
                    log.info("deployFile文件:{},成功查询到软件code:{}对应文件", key, svcCode);
                    //查找deploy文件
                    List<File> files = entry.getValue();
                    Optional<File> first = files.stream().filter(file -> file.getName().toLowerCase().endsWith("-deploy.yaml")).findFirst();
                    if (first.isPresent()) {
                        //匹配到xx系统的deploy文件,读取文件内容
                        File deployFile = first.get();
                        String parent = deployFile.getParent();
                        log.info("deployFile:{}的父级目录路径：{}", deployFile.getName(), parent);
                        //存入本地保存的父级目录，共后面找到真实的路径读取文件内容
                        deploySoftwareAppInfo.setBuildPackSavePath(parent);
                        String deployFileContent = FileUtils.readFileToString(deployFile, "UTF-8");
                        Map<String, Object> load = yaml.load(deployFileContent);
                        // 2. 转换为JSON字符串
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        String json = gson.toJson(load);
                        //3.  将JSON字符串转换为对象
                        KubernetesDeployment obj = JacksonUtils.toObj(json, KubernetesDeployment.class);

                        deploySoftwareAppInfo.setKind(obj.getKind());
                        deploySoftwareAppInfo.setReplicas(obj.getSpec().getReplicas());
                        deploySoftwareAppInfo.setNamespace(obj.getMetadata().getNamespace());

                        SoftwareDeployEnvInfo softwareDeployEnvInfo;
                        SoftwareDeployPortsInfo softwareDeployPortsInfo;
                        //获取metadata:annotations: k8s.unitechs.com/clusterId: "360403983c98ecaf70081ecde665b4da",暂时不入库了，反正都需要读取文件
//                        Map<String, String> annotations = obj.getMetadata().getAnnotations();
//                        if (CollUtil.isNotEmpty(annotations)&&annotations.containsKey("k8s.unitechs.com/clusterId")) {
//                            deploySoftwareAppInfo.setClusterId(annotations.get("k8s.unitechs.com/clusterId"));
//                        }

                        //4. 获取容器信息
                        for (KubernetesDeployment.Container container : obj.getSpec().getTemplate().getSpec().getContainers()) {

                            deploySoftwareAppInfo.setImageName(container.getImage());
                            //环境变量赋值入库对象
                            List<KubernetesDeployment.EnvVar> env = container.getEnv();
                            for (KubernetesDeployment.EnvVar envVar : env) {
                                softwareDeployEnvInfo = new SoftwareDeployEnvInfo();
                                softwareDeployEnvInfo.setId(snowflakeIdGenerator.nextId());
                                softwareDeployEnvInfo.setDeployInfoId(id);
                                softwareDeployEnvInfo.setName(envVar.getName());
                                softwareDeployEnvInfo.setValue(envVar.getValue());
                                softwareDeployEnvInfoList.add(softwareDeployEnvInfo);
                            }
                            List<KubernetesDeployment.ContainerPort> ports = container.getPorts();
                            for (KubernetesDeployment.ContainerPort port : ports) {
                                softwareDeployPortsInfo = new SoftwareDeployPortsInfo();
                                softwareDeployPortsInfo.setId(snowflakeIdGenerator.nextId());
                                softwareDeployPortsInfo.setDeployInfoId(id);
                                softwareDeployPortsInfo.setId(snowflakeIdGenerator.nextId());
                                softwareDeployPortsInfo.setName(port.getName());
                                softwareDeployPortsInfo.setPort(port.getContainerPort());
                                softwareDeployPortsInfo.setProtocol(port.getProtocol());
                                softwareDeployPortsInfoList.add(softwareDeployPortsInfo);
                            }
                        }
                    }
                }
            }
        }
        if (CollUtil.isNotEmpty(softwareDeployEnvInfoList)) {
            softwareDeployEnvInfoMapper.batchInsert(softwareDeployEnvInfoList);
        }
        if (CollUtil.isNotEmpty(softwareDeployPortsInfoList)) {
            softwareDeployPortsInfoMapper.batchInsert(softwareDeployPortsInfoList);
        }
        //更新软件名称
        softwareDeploySoftwareAppInfoMapper.updateBatch(deploySoftwareAppInfoAlls);
    }

    @Override
    public ApiResponse<PageInfo<SoftwareDeploySoftwareInfo>> productPage(QueryDeploySoftwareInfoDto queryDeploySoftwareInfo, int pageNo, int pageSize) {
        try {
            PageInfo<SoftwareDeploySoftwareInfo> pageInfo = PageHelper.startPage(pageNo, pageSize).doSelectPageInfo(() -> softwareDeploySoftwareInfoMapper.selectAll(queryDeploySoftwareInfo));
            return new ApiResponse<>("200", "查询成功", pageInfo);
        } catch (Exception e) {
            log.error("软件部署_软件制品列表查询失败:{}", e.getMessage());
            return new ApiResponse<>("500", "查询失败," + e.getMessage(), null);
        }
    }

    @Override
    public ApiResponse<SoftwareAndAppInfoResponse> selectDetailByDeployId(Long deployId) {
        try {
            SoftwareAndAppInfoResponse softwareAndAppInfoResponse = softwareDeploySoftwareInfoMapper.selectAllSoftwareAndAppInfo(deployId);
            return new ApiResponse<>("200", "查询成功", softwareAndAppInfoResponse);
        } catch (Exception e) {
            log.error("查询失败:{}", e.getMessage());
            return new ApiResponse<>("500", "查询失败," + e.getMessage(), null);
        }
    }

    @Override
    public ApiResponse<List<SoftwareDeploySoftwareAppInfo>> selectConfig(Long deployId) {
        log.info("查询软件部署信息中软件对应的配置文件信息：{}", deployId);
        return new ApiResponse<>("200", "查询成功", softwareDeploySoftwareAppInfoMapper.selectByDeployId(deployId));
    }

    @Override
    public ApiResponse<Void> saveConfig(List<SoftwareDeploySoftwareAppInfo> softwareAppInfos) {
        if (CollUtil.isNotEmpty(softwareAppInfos)) {
            //同步更新yaml文件中的属性、环境变量、端口信息
            try {
                updateYamlFile(softwareAppInfos);
            } catch (Exception e) {
                log.error("更新yaml文件失败:{}", e.getMessage());
            }

            //更新具体软件属性
            softwareDeploySoftwareAppInfoMapper.updateBatch(softwareAppInfos);
            //软件app主键Id
            List<Long> softwareAppIds = softwareAppInfos.stream().map(SoftwareDeploySoftwareAppInfo::getId).collect(Collectors.toList());
            //更新环境变量信息,1.先删除2.再添加
            softwareDeployEnvInfoMapper.deleteBySoftwareAppIds(softwareAppIds);
            List<SoftwareDeployEnvInfo> envInfos = softwareAppInfos.stream().filter(x -> CollUtil.isNotEmpty(x.getSoftwareDeployEnvInfoList()))
                    .flatMap(x -> x.getSoftwareDeployEnvInfoList().stream()).collect(Collectors.toList());
            if (CollUtil.isNotEmpty(envInfos)) {
                softwareDeployEnvInfoMapper.batchInsert(envInfos);
            }
            //更新端口信息,1.先删除2.再添加
            softwareDeployPortsInfoMapper.deleteBySoftwareAppIds(softwareAppIds);
            List<SoftwareDeployPortsInfo> softwareDeployPortsInfos = softwareAppInfos.stream().filter(x -> CollUtil.isNotEmpty(x.getSoftwareDeployPortsInfoList())).flatMap(x -> x.getSoftwareDeployPortsInfoList().stream())
                    .collect(Collectors.toList());
            if (CollUtil.isNotEmpty(softwareDeployPortsInfos)) {
                softwareDeployPortsInfoMapper.batchInsert(softwareDeployPortsInfos);
            }

            return new ApiResponse<>("200", "保存成功", null);
        }
        log.error("保存部署软件配置信息失败，参数为空");
        return new ApiResponse<>("500", "保存失败", null);
    }

    /**
     * 获取 clusterId
     * 优先级：softwareAppInfo.getClusterId() > defaultClusterId (Nacos配置) > null
     *
     * @param softwareAppInfo 软件应用信息
     * @return clusterId，如果都不存在则返回 null
     */
    private String getClusterId(SoftwareDeploySoftwareAppInfo softwareAppInfo) {
        // 优先使用 softwareAppInfo 中已设置的 clusterId
        if (StringUtils.isNotBlank(softwareAppInfo.getClusterId())) {
            return softwareAppInfo.getClusterId();
        }

        // 如果为空，从 Nacos 配置读取
        if (StringUtils.isNotBlank(defaultClusterId)) {
            return defaultClusterId;
        }

        return null;
    }

    /**
     * 动态修改yaml文件
     * 解压后的目录地址  buildPackSavePath +File.separator+ systemCode+File.separator+deployId;
     *
     * @param softwareAppInfos
     */
    private void updateYamlFile(List<SoftwareDeploySoftwareAppInfo> softwareAppInfos) throws IOException {
        for (SoftwareDeploySoftwareAppInfo softwareAppInfo : softwareAppInfos) {
            String sysCode = softwareAppInfo.getSvcCode();
            Long deployId = softwareAppInfo.getDeploySoftwareInfoId();
            String buildPackSavePath = softwareAppInfo.getBuildPackSavePath();
            log.info("[动态修改yaml文件]软件编码:{},构建包存储路劲:{},部署目录id:{}", sysCode, buildPackSavePath, deployId);

            // 获取 clusterId 并设置到 softwareAppInfo
            String clusterId = getClusterId(softwareAppInfo);
            if (StringUtils.isNotBlank(clusterId)) {
                softwareAppInfo.setClusterId(clusterId);
            }

            //这里只能匹配deploy.yaml,因为文件带了前缀
            Path path = Paths.get(buildPackSavePath);
            if (!Files.exists(path)) {
                log.error("[动态修改yaml文件]构建表的父级文件路径不存在:{}", buildPackSavePath);
            } else {
                File dir = new File(path.toString());
                File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith("-deploy.yaml"));
                if (jsonFiles != null) {
                    if (jsonFiles.length > 0) {
                        log.warn("[动态修改yaml文件]目录存在多个deploy.yaml:{}", buildPackSavePath);
                    }
                    File deployYamlFile = jsonFiles[0];
                    Path inputYamlFilePath = deployYamlFile.toPath();
                    Path bakYamlFilePath = Paths.get(deployYamlFile.getPath() + ".bak");
                    //备份文件
                    Files.copy(inputYamlFilePath, bakYamlFilePath);
                    //读取对应系统的部署yaml文件
                    //Path outputYamlFilePath = Paths.get(buildPackSavePath + File.separator + sysCode + "-deploy-" + deployId + ".yaml");
                    List<KubernetesConfigEditor.YamlOperation> operations = new ArrayList<>();
                    //通过反射构建yaml路径对应的值对象（clusterId 由文本替换处理）
                    builderYamlOperations(operations, softwareAppInfo);
                    if (!operations.isEmpty()) {
                        KubernetesConfigEditor.updateDeployYaml(operations, bakYamlFilePath, inputYamlFilePath);
                    } else {
                        log.info("[动态修改yaml文件]无其他字段变更，跳过YAML重写: {}", inputYamlFilePath);
                    }
                    // 单独更新 clusterId，避免路径解析失败并尽量保持格式
                    if (StringUtils.isNotBlank(clusterId)) {
                        updateClusterIdInYamlFile(inputYamlFilePath, clusterId);
                    }
                } else {
                    log.error("[动态修改yaml文件]文件路径:{}下找到-deploy.yaml文件", path);
                }
            }
        }
    }

    /**
     * 基于文本替换更新 YAML 中的 clusterId，尽量保持原有格式
     *
     * @param yamlFilePath yaml文件路径
     * @param clusterId    新的clusterId
     * @return true 表示有替换发生
     */
    private boolean updateClusterIdInYamlFile(Path yamlFilePath, String clusterId) throws IOException {
        if (StringUtils.isBlank(clusterId)) {
            return false;
        }
        String content = new String(Files.readAllBytes(yamlFilePath), StandardCharsets.UTF_8);
        Matcher matcher = CLUSTER_ID_LINE_PATTERN.matcher(content);
        int replaceCount = 0;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String quote = StringUtils.isNotBlank(matcher.group(2)) ? matcher.group(2) : matcher.group(4);
            if (quote == null) {
                quote = "";
            }
            String replacement = prefix + quote + clusterId + quote;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            replaceCount++;
        }
        if (replaceCount == 0) {
            log.warn("[动态修改yaml文件]未找到clusterId配置行, path: {}", yamlFilePath);
            return false;
        }
        matcher.appendTail(sb);
        String newContent = sb.toString();
        if (!content.equals(newContent)) {
            Files.write(yamlFilePath, newContent.getBytes(StandardCharsets.UTF_8));
            log.info("[动态修改yaml文件]已更新clusterId, path: {}, count: {}", yamlFilePath, replaceCount);
        }
        return true;
    }

    private void builderYamlOperations(List<KubernetesConfigEditor.YamlOperation> operations, SoftwareDeploySoftwareAppInfo softwareAppInfo) {
        log.info("[构建YAML操作]开始处理软件应用信息，svcCode: {}", softwareAppInfo.getSvcCode());
        Class<? extends SoftwareDeploySoftwareAppInfo> aClass = softwareAppInfo.getClass();
        Field[] fields = aClass.getDeclaredFields();
        log.debug("[构建YAML操作]扫描到 {} 个字段", fields.length);

        for (Field field : fields) {
            String fieldName = field.getName();

            // clusterId 使用文本替换更新，避免路径解析失败
            if ("clusterId".equals(fieldName)) {
                log.debug("[构建YAML操作]字段 {} 由文本替换处理，跳过", fieldName);
                continue;
            }

            // 处理需要更新多个路径的字段（如 clusterId）
            if (MULTI_PATH_FIELDS.containsKey(fieldName)) {
                log.debug("[构建YAML操作]处理多路径字段: {}", fieldName);
                try {
                    field.setAccessible(true);
                    Object value = field.get(softwareAppInfo);
                    if (value instanceof String && StringUtils.isNotBlank((String) value)) {
                        List<String> paths = MULTI_PATH_FIELDS.get(fieldName);
                        log.info("[构建YAML操作]字段 {} 的值: {}, 需要更新的路径数: {}", fieldName, value, paths.size());
                        for (String path : paths) {
                            operations.add(new KubernetesConfigEditor.ModifyOperation(path, value));
                            log.debug("[构建YAML操作]添加修改操作: {} = {}", path, value);
                        }
                    } else {
                        log.debug("[构建YAML操作]字段 {} 的值为空，跳过", fieldName);
                    }
                } catch (IllegalAccessException e) {
                    log.error("[构建YAML操作]处理多路径字段 {} 时出错: {}", fieldName, e.getMessage(), e);
                }
                continue;
            }

            // 处理带有 @YamlPath 注解的字段
            if (field.isAnnotationPresent(YamlPath.class)) {
                YamlPath annotation = field.getAnnotation(YamlPath.class);
                String path = annotation.value();
                log.debug("[构建YAML操作]处理字段: {}, YAML路径: {}", fieldName, path);
                try {
                    field.setAccessible(true);
                    Object value = field.get(softwareAppInfo);
                    if (value instanceof List) {
                        List<?> listValue = (List<?>) value;
                        log.info("[构建YAML操作]字段 {} 是列表类型，路径: {}, 列表大小: {}", fieldName, path, listValue != null ? listValue.size() : 0);
                        if (CollUtil.isNotEmpty(listValue)) {
                            boolean isPortsPath = path.contains("ports");  // 判断是否是 ports 列表

                            // ========== 问题2修复：containerPort 为 null 时保持原样 ==========
                            // 需求：如果 ports 列表中有任何一个 containerPort 为 null，整个 ports 节点都不要更新
                            // 原因：避免覆盖原 YAML 中有效的 containerPort 值
                            if (isPortsPath) {
                                log.info("[构建YAML操作]检测到 ports 列表，开始检查 containerPort 字段");
                                boolean hasNullContainerPort = false;
                                int itemIndex = 0;
                                // 先遍历所有端口项，检查是否有任何 containerPort 为 null
                                for (Object item : listValue) {
                                    itemIndex++;
                                    Class<?> aClass1 = item.getClass();
                                    Field[] fields1 = aClass1.getDeclaredFields();
                                    log.debug("[构建YAML操作]检查端口项 {}: {}", itemIndex, item.getClass().getSimpleName());
                                    for (Field field1 : fields1) {
                                        if (field1.isAnnotationPresent(YamlPath.class)) {
                                            YamlPath annotation1 = field1.getAnnotation(YamlPath.class);
                                            String path1 = annotation1.value();
                                            // 检查 containerPort 字段
                                            if ("containerPort".equals(path1)) {
                                                try {
                                                    field1.setAccessible(true);
                                                    Object fieldValue = field1.get(item);
                                                    log.debug("[构建YAML操作]端口项 {} 的 containerPort 值: {}", itemIndex, fieldValue);
                                                    if (fieldValue == null) {
                                                        hasNullContainerPort = true;
                                                        log.warn("[构建YAML操作]端口项 {} 的 containerPort 为 null，将跳过整个 ports 节点的更新", itemIndex);
                                                        break;  // 找到 null，跳出内层循环
                                                    }
                                                } catch (Exception e) {
                                                    log.error("[构建YAML操作]检查端口项 {} 的 containerPort 字段时出错: {}", itemIndex, e.getMessage(), e);
                                                }
                                            }
                                        }
                                    }
                                    if (hasNullContainerPort) {
                                        break;  // 找到 null，跳出外层循环
                                    }
                                }

                                // 如果 ports 列表中有任何一个 containerPort 为 null，跳过整个 ports 节点的更新
                                // 这样原 YAML 中的 ports 配置保持不变
                                if (hasNullContainerPort) {
                                    log.info("[构建YAML操作]ports 列表中存在 containerPort 为 null 的项，跳过整个 ports 节点的更新，保持原 YAML 不变");
                                    continue;  // 跳过这个字段的处理，保持原 YAML 不变
                                } else {
                                    log.info("[构建YAML操作]ports 列表中所有 containerPort 都不为 null，继续处理");
                                }
                            }
                            // ========== 问题2修复结束 ==========

                            // ========== 问题1修复：Map 复用导致的重复项问题 ==========
                            // 问题：之前 newMap 在循环外创建，导致所有 item 共享同一个 Map 对象
                            // 结果：后面的字段值会覆盖前面的值，同一个 Map 被多次添加到列表
                            // 修复：为每个 item 创建独立的 Map，处理完所有字段后再添加到列表
                            List<Map<String, Object>> newList = new ArrayList<>();
                            int itemIndex = 0;
                            for (Object item : listValue) {
                                itemIndex++;
                                log.debug("[构建YAML操作]处理列表项 {}: {}", itemIndex, item.getClass().getSimpleName());
                                // 关键修复：为每个 item 创建新的 Map，避免 Map 复用导致的值覆盖问题
                                Map<String, Object> newMap = new HashMap<>();
                                Class<?> aClass1 = item.getClass();
                                Field[] fields1 = aClass1.getDeclaredFields();

                                // 处理所有字段，收集非 null 的值
                                int fieldCount = 0;  // 记录添加到 Map 的字段数量
                                for (Field field1 : fields1) {
                                    if (field1.isAnnotationPresent(YamlPath.class)) {
                                        YamlPath annotation1 = field1.getAnnotation(YamlPath.class);
                                        String path1 = annotation1.value();
                                        try {
                                            field1.setAccessible(true);
                                            Object fieldValue = field1.get(item);
                                            // 过滤掉 null 值，避免写入 null 到 YAML
                                            if (fieldValue != null) {
                                                newMap.put(path1, fieldValue);
                                                fieldCount++;
                                                log.debug("[构建YAML操作]列表项 {} 添加字段: {} = {}", itemIndex, path1, fieldValue);
                                            } else {
                                                log.debug("[构建YAML操作]列表项 {} 的字段 {} 值为 null，跳过", itemIndex, path1);
                                            }
                                        } catch (Exception e) {
                                            log.error("[构建YAML操作]处理列表项 {} 的字段 {} 时出错: {}", itemIndex, field1.getName(), e.getMessage(), e);
                                        }
                                    }
                                }

                                // 关键修复：处理完该 item 的所有字段后，再添加到列表
                                // 这样确保每个 item 都有独立的 Map 对象，不会出现值覆盖
                                if (!newMap.isEmpty()) {
                                    newList.add(newMap);
                                    log.debug("[构建YAML操作]列表项 {} 处理完成，添加了 {} 个字段，Map 大小: {}, 已添加到列表", itemIndex, fieldCount, newMap.size());
                                } else {
                                    log.warn("[构建YAML操作]列表项 {} 处理完成，但 Map 为空（有效字段数: {}），跳过", itemIndex, fieldCount);
                                }
                            }
                            // ========== 问题1修复结束 ==========

                            // 只有当列表不为空时才添加操作
                            if (!newList.isEmpty()) {
                                operations.add(new KubernetesConfigEditor.ReplaceOperation(path, newList));
                                log.info("[构建YAML操作]字段 {} 处理完成，路径: {}, 添加了 {} 个列表项到操作列表", fieldName, path, newList.size());
                            } else {
                                log.warn("[构建YAML操作]字段 {} 处理完成，但列表为空，不添加操作", fieldName);
                            }
                        } else {
                            log.debug("[构建YAML操作]字段 {} 的列表为空，跳过", fieldName);
                        }
                    } else if (value instanceof String) {
                        if (StringUtils.isNotBlank((String) value)) {
                            operations.add(new KubernetesConfigEditor.ModifyOperation(path, value));
                            log.info("[构建YAML操作]字段 {} 处理完成，路径: {}, 值: {}", fieldName, path, value);
                        } else {
                            log.debug("[构建YAML操作]字段 {} 的字符串值为空，跳过", fieldName);
                        }
                    } else {
                        log.debug("[构建YAML操作]字段 {} 的值类型: {}, 跳过", fieldName, value != null ? value.getClass().getSimpleName() : "null");
                    }
                } catch (IllegalAccessException e) {
                    log.error("[构建YAML操作]处理字段 {} 时出错: {}", field.getName(), e.getMessage(), e);
                }
            }
        }
        log.info("[构建YAML操作]处理完成，共生成 {} 个操作", operations.size());
    }

    @Override
    public ApiResponse<List<SoftwareProductListResponse>> listSoftwareProduct(Long id) {
        return new ApiResponse<>("200", "查询成功", softwareDeploySoftwareAppInfoMapper.listSoftwareProduct(id));
    }

    @Override
    public ApiResponse<List<SoftwareDeploySoftwareAppInfo>> makeProduct(List<SoftwareProductListResponse> mkProductList, String accessToken) {
        if (CollUtil.isEmpty(mkProductList)) {
            log.error("软件安装失败，参数为空");
            return new ApiResponse<>("500", "软件安装失败，参数为空", null);
        }
        BaseDirectoryProperties.DirectoryConfig software = baseDirectoryProperties.getServices().get("software");
        // 1. 生成Basic Auth
        String auth = dzUserName + ":" + dzPW;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        String authHeader = "Basic " + encodedAuth;

        //获取当前登录用户下授权角色列表，后续2.3.7.无状态服务部署接口、2.3.8.服务部署接口、2.3.9.配置字典部署接口中roleIds参数需要用到
        List<Integer> roleIds = queryRoleIdsByApiGet(accessToken);

        //入库更新状态
        List<SoftwareDeploySoftwareAppInfo> softwareDeploySoftwareAppInfoList = new ArrayList<>(mkProductList.size());
        SoftwareDeploySoftwareAppInfo softwareDeploySoftwareAppInfo;
        //遍历软件安装接口的上下文
        SoftwareDeployContext context;
        //应用构建包上一级路径,查找plat_config_data_1.json
        String buildPrefixSavePath = null;

        SoftwareProductListResponse productListResponse = mkProductList.get(0);
        String buildPackSavePath = productListResponse.getBuildPackSavePath();
        String namespace = productListResponse.getNamespace();
        //D:\work\项目文档\2025\CBG-XW多中心协同\真实的构建包\hyy-test\hyy-bpce-ds-server 取父路径
        buildPrefixSavePath = Paths.get(buildPackSavePath).getParent().toString();
        log.info("构建表的顶级文件路径:{},用于获取最外层json与buildInfo.yaml", buildPrefixSavePath);
        BuildInfoModel buildInfoYaml = buildBuildInfoYaml(buildPrefixSavePath);

        //应用构建参数
        Map<String, Object> appMakeRequestMap = new HashMap<>();
        List<String> svcCodeList = new ArrayList<>();
        appMakeRequestMap.put("appCodeList", svcCodeList);
        //clusterId 取所有的？
        List<String> clusterIds = Lists.newArrayList();
        //遍历安装引用列表，执行2.3.1->2.3.9
        for (SoftwareProductListResponse softwareProductListResponse : mkProductList) {
            context = new SoftwareDeployContext();
            softwareDeploySoftwareAppInfo = new SoftwareDeploySoftwareAppInfo();
            softwareDeploySoftwareAppInfo.setId(softwareProductListResponse.getId());
            softwareDeploySoftwareAppInfo.setSysCode(softwareProductListResponse.getSysCode());
            softwareDeploySoftwareAppInfo.setSysName(softwareProductListResponse.getSysName());
            softwareDeploySoftwareAppInfo.setSvcCode(softwareProductListResponse.getSvcCode());
            softwareDeploySoftwareAppInfo.setSvcName(softwareProductListResponse.getSvcName());
            softwareDeploySoftwareAppInfoList.add(softwareDeploySoftwareAppInfo);

            appMakeRequestMap.put("systemCode", softwareProductListResponse.getSysCode());
            svcCodeList.add(softwareProductListResponse.getSvcCode());
            //通用软件请求地址与请求头
            context.put("ipAndPort", software.getUrl() + ":" + software.getPort());
            context.put("authorization", authHeader);
            //存入角色Id
            context.put("roleIds", roleIds);
            context.put("deploySoftwareInfoId", softwareProductListResponse.getDeploySoftwareInfoId());
            context.put("softwareInstallPath", softwareProductListResponse.getSoftwareInstallPath());
            //构建包解压的存储路径
            context.put("buildPackSavePath", softwareProductListResponse.getBuildPackSavePath());
            //部署软件code
            context.put("svcCode", softwareProductListResponse.getSvcCode());
            //设置accessToken
            context.put("accessToken", accessToken);
            //镜像推送入参(buildInfo.yaml)
            buildImagePushRequestParam(buildInfoYaml, softwareProductListResponse, context);
            //无状态服务部署入参(deploy.yaml)
            buildDeployCreateRequestParam(softwareProductListResponse, context, roleIds, clusterIds);
            //服务部署接口入参与 构建2.3.9.服务路由配置接口（都从-svc.yaml取内容）
            buildServiceDeployRequestParam(buildInfoYaml, softwareProductListResponse, context, roleIds);
            //配置字典部署接口入参(新提供的.yaml,在单独的文件夹中)
            //buildDictConfigRequestParam(softwareProductListResponse, context);
            //应用安装入参（2.3.11.应用构建接口）
            //buildAppInstallRequestParam(softwareProductListResponse, context);
            try {
                softwareDeployInstallHandler.handle(context);
                softwareDeploySoftwareAppInfo.setDeployTime(LocalDateTime.now());
                String errorMsg = context.get("errorMsg");
                if (StringUtils.isNotBlank(errorMsg)) {
                    log.error("软件部署执行步骤失败,安装失败的错误信息：{}", errorMsg);
                    softwareDeploySoftwareAppInfo.setDeployStatus("失败");
                } else {
                    softwareDeploySoftwareAppInfo.setDeployStatus("成功");
                }
            } catch (Throwable e) {
                log.error("软件部署执行步骤失败,安装失败的异常信息：{}", e.getMessage());
                softwareDeploySoftwareAppInfo.setDeployTime(LocalDateTime.now());
                softwareDeploySoftwareAppInfo.setDeployStatus("失败");
            }
        }
        //2.3.11.应用构建接口
        //appMakeSoftwareDeplyHandel(appMakeRequestMap);
        //2.3.8.配置字典部署接口-configMapDeployService
        softwareDeployConfigMapHandle(buildPrefixSavePath, namespace, roleIds, clusterIds);
        //2.4.2.应用安装接口--不需要了
        boolean success = appInstallSoftwareDeployHandel(buildPrefixSavePath, accessToken);
        //软件调用记录--入库???
        SoftwareInstallRecord softwareInstallRecord = new SoftwareInstallRecord();
        SnowflakeIdGenerator snowflakeIdGenerator = new SnowflakeIdGenerator(1, 1);
        softwareInstallRecord.setId(snowflakeIdGenerator.nextId());
        //消费者->当前中心
        softwareInstallRecord.setConsumer(centerName);
        softwareInstallRecord.setBelongSoftware(productListResponse.getSoftwareName());
        softwareInstallRecord.setSoftwareVersion(productListResponse.getSoftwareVersion());
        softwareInstallRecord.setDownloadTime(LocalDateTime.now());
        softwareInstallRecord.setDeployTaskApprovalStatus(success ? 1 : 0);
        softwareInstallRecord.setDeployTaskApprovalStatusName(success ? "通过" : "未通过");
        softwareInstallRecordMapper.insert(softwareInstallRecord);
        //在这里入库，模拟调用4+2成功
        softwareDeploySoftwareAppInfoMapper.updateBatchSelective(softwareDeploySoftwareAppInfoList);
        return new ApiResponse<>("200", "软件部署执行成功", softwareDeploySoftwareAppInfoList);
    }

    /**
     * 获取角色id
     *
     * @param accessToken
     * @return
     */
    private List<Integer> queryRoleIdsByApiGet(String accessToken) {
        BaseDirectoryProperties.DirectoryConfig application = baseDirectoryProperties.getServices().get("gw-service");
        String realRequestUrl = String.format(SoftwarePipelineConfig.SoftwareDeployInstallUrlEnum.ROLE_IDS_QUERY_URL.getUrl(), application.getUrl() + ":" + application.getPort());
        Map<String, String> headers = new HashMap<>();
        headers.put("access-token", accessToken);
        log.info("[获取当前登录用户下授权角色列表接口]URL:{},header:{}", realRequestUrl, headers);
        try (HttpClientApiCaller httpClientApiCaller = new HttpClientApiCaller()) {
            ApiCallerResponse<List<ApiRoleInfoResponse>> execute = httpClientApiCaller.execute(realRequestUrl,
                    SoftwarePipelineConfig.SoftwareDeployInstallUrlEnum.ROLE_IDS_QUERY_URL.getMethod(),
                    headers, null, null, null,
                    HttpClientApiCaller.ResponseHandlers.json(new com.fasterxml.jackson.core.type.TypeReference<ApiCallerResponse<List<ApiRoleInfoResponse>>>() {
                    }));
            log.info("[获取当前登录用户下授权角色列表接口]响应:{}", JacksonUtils.toJson(execute));
            String code = execute.getCode();
            if (StringUtils.equalsIgnoreCase("0000", code)) {
                log.info("[获取当前登录用户下授权角色列表接口]成功{}", code);
                List<ApiRoleInfoResponse> apiRoleInfoResponses = execute.getData();
                if (CollUtil.isNotEmpty(apiRoleInfoResponses)) {
                    return apiRoleInfoResponses.stream().map(ApiRoleInfoResponse::getId).distinct().collect(Collectors.toList());
                }
            } else {
                log.info("[获取当前登录用户下授权角色列表接口]失败{}", code);
            }
        } catch (Exception e) {
            log.error("[获取当前登录用户下授权角色列表接口]异常:{}", e.getMessage());
        }
        //如果没查到角色ID，则使用默认角色ID
        String defaultRoleIds = softwareDeployConfig.getRoleIds();
        if (StringUtils.isBlank(defaultRoleIds)) {
            log.warn("[获取当前登录用户下授权角色列表接口]默认角色ID未配置，返回空列表");
            return new ArrayList<>();
        }
        return Arrays.stream(defaultRoleIds.split(",")).map(Integer::parseInt).collect(Collectors.toList());
    }

    /**
     * 2.3.8.配置字典部署接口
     *
     * @param buildPrefixSavePath
     */
    private void softwareDeployConfigMapHandle(String buildPrefixSavePath, String namespace, List<Integer> roleIds, List<String> clusterIds) {
        Path path = Paths.get(buildPrefixSavePath).resolve("configmap");
        if (!Files.exists(path)) {
            log.error("[配置字典部署接口]构建表的父级文件路径不存在:{}", buildPrefixSavePath);
        } else {
            File dir = new File(path.toString());
            File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith("-cm.yaml"));
            if (jsonFiles != null) {
                for (File jsonFile : jsonFiles) {
                    try {
                        String yamlContent = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
                        Map<String, Object> deploySoftwareMap = new HashMap<>();
//                        deploySoftwareMap.put("namespace", namespace);
                        deploySoftwareMap.put("yaml", yamlContent);
                        deploySoftwareMap.put("roleIds", roleIds);
                        log.info("[配置字典部署接口]clusterIds:{}", clusterIds);
                        if (CollUtil.isNotEmpty(clusterIds)) {
                            for (String clusterId : clusterIds) {
                                deploySoftwareMap.put("clusterId", clusterId);
                                SoftwareDeployContext dictConfigContext = new SoftwareDeployContext();
                                dictConfigContext.put("dictConfigRequest", JacksonUtils.toJson(deploySoftwareMap));
                                configMapDeployService.handle(dictConfigContext);
                            }
                        } else {
                            log.error("[配置字典部署接口]未指定clusterId，不执行....");
                        }
                    } catch (IOException e) {
                        log.error("配置字典部署接口读取.json文件内容异常：{}", e.getMessage());
                    } catch (Exception e) {
                        log.error("配置字典部署接口执行发生异常：{}", e.getMessage());
                    }
                }
            } else {
                log.error("配置字典部署接口,未在父级文件路径:{}下找到-cm.yaml", path);
            }
        }
    }

    /**
     * 2.4.2应用安装接口 .json文件
     *
     * @param buildPrefixSavePath
     * @param accessToken
     */
    private boolean appInstallSoftwareDeployHandel(String buildPrefixSavePath, String accessToken) {
        Path path = Paths.get(buildPrefixSavePath);
        if (!Files.exists(path)) {
            log.error("[plat_config_data_1.json]构建表的父级文件路径不存在:{}", buildPrefixSavePath);
            return false;
        } else {
            boolean flag = true;
            File dir = new File(path.toString());
            File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
            if (jsonFiles != null) {
                for (File jsonFile : jsonFiles) {
                    try {
                        String sqlFileContent = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
                        SoftwareDeployContext appInstallContext = new SoftwareDeployContext();
                        appInstallContext.put("appInstallRequest", sqlFileContent);
                        appInstallContext.put("accessToken", accessToken);
                        softwareAppInstallService.handle(appInstallContext);
                    } catch (IOException e) {
                        log.error("应用安装接口读取.json文件内容异常：{}", e.getMessage());
                        flag = false;
                    } catch (Exception e) {
                        log.error("应用安装接口执行发生异常：{}", e.getMessage());
                        flag = false;
                    }
                }
            } else {
                log.error("2.4.2应用安装接口,未在父级文件路径:{}下找到.json文件", path);
                flag = false;
            }
            return flag;
        }
    }

    private void appMakeSoftwareDeplyHandel(Map<String, Object> appMakeRequestMap) {
        try {
            //{ "systemCode": "BASE", "appCodeList": [ "base", "nms" ] }
            SoftwareDeployContext afterRouteContext = new SoftwareDeployContext();
            afterRouteContext.put("appMakeRequestMap", JacksonUtils.toJson(appMakeRequestMap));
            softwareAppMakeService.handle(afterRouteContext);
        } catch (Exception e) {
            log.error("应用构建接口执行发生异常：{}", e.getMessage());
        }
    }

    /**
     * 构建2.3.8.	配置字典部署接口
     *
     * @param softwareProductListResponse
     * @param context
     */
    private void buildDictConfigRequestParam(SoftwareProductListResponse softwareProductListResponse, SoftwareDeployContext context, List<String> roleIds) {
        String buildPackSavePath = softwareProductListResponse.getBuildPackSavePath();
        if (buildPackSavePath == null || buildPackSavePath.isEmpty()) {
            log.warn("构建配置字典部署接口入参，buildPackSavePath为空");
            return;
        }
        Path buildPrefixSavePath = Paths.get(buildPackSavePath);
        if (!Files.exists(buildPrefixSavePath)) {
            log.error("[构建配置字典部署接口入参]构建表的文件路径:{}不存在!", buildPrefixSavePath);
        } else {
            File dir = new File(buildPackSavePath);
            File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith("-xx.yaml"));
            if (jsonFiles != null) {
                if (jsonFiles.length > 1) {
                    log.warn("[构建配置字典部署接口入参]构建表文件数量大于1,请检查构建表文件数量!");
                }
                File dictConfigFile = jsonFiles[0];
                Map<String, Object> deploySoftwareMap = new HashMap<>();
                try {
                    String yamlContent = FileUtils.readFileToString(dictConfigFile, "UTF-8");
                    deploySoftwareMap.put("namespace", softwareProductListResponse.getNamespace());
                    deploySoftwareMap.put("yaml", yamlContent);
                    deploySoftwareMap.put("roleIds", roleIds);
                    context.put("dictConfigRequest", JacksonUtils.toJson(deploySoftwareMap));
                } catch (IOException e) {
                    log.error("构建配置字典部署接口入参,异常:{}", e.getMessage(), e);
                }
            } else {
                log.error("2.4.2应用安装接口,未在父级文件路径:{}下找到-xx.yaml文件", buildPrefixSavePath);
            }
        }
    }

    /**
     * 构建2.3.7.服务部署接口、2.3.9.服务路由配置接口入参
     *
     * @param buildInfoYaml buildInfo.yaml
     * @param softwareProductListResponse
     * @param context
     */
    private void buildServiceDeployRequestParam(BuildInfoModel buildInfoYaml, SoftwareProductListResponse softwareProductListResponse, SoftwareDeployContext context, List<Integer> roleIds) {
        String buildPackSavePath = softwareProductListResponse.getBuildPackSavePath();
        if (buildPackSavePath == null || buildPackSavePath.isEmpty()) {
            log.warn("构建服务部署接口与路由配置接口入参，buildPackSavePath为空");
            return;
        }

        Path buildPrefixSavePath = Paths.get(buildPackSavePath);
        if (!Files.exists(buildPrefixSavePath)) {
            log.error("[构建服务部署接口与路由配置接口入参]构建表的文件路径:{}不存在!", buildPrefixSavePath);
        } else {
            File dir = new File(buildPackSavePath);
            File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith("-svc.yaml"));
            if (jsonFiles != null) {
                if (jsonFiles.length > 1) {
                    log.warn("[构建服务部署接口与路由配置接口入参]构建表文件数量大于1,请检查构建表文件数量!");
                }
                File dictConfigFile = jsonFiles[0];
                parseAndBuildRouteAddParam(buildInfoYaml, softwareProductListResponse, context, dictConfigFile.toPath(), roleIds);
            } else {
                log.error("[构建服务部署接口与路由配置接口入参]未在文件路径:{}下找到-svc.yaml文件", buildPrefixSavePath);
            }
        }
    }

    private void parseAndBuildRouteAddParam(BuildInfoModel buildInfoYaml, SoftwareProductListResponse softwareProductListResponse,
                                            SoftwareDeployContext context, Path outputYamlFilePath, List<Integer> roleIds) {
        Yaml yaml = new Yaml();
        try {
            Map<String, Object> deploySoftwareMap = new HashMap<>();
            byte[] bytes = Files.readAllBytes(outputYamlFilePath);
            String yamlContent = new String(bytes, StandardCharsets.UTF_8);
            deploySoftwareMap.put("namespace", softwareProductListResponse.getNamespace());
            deploySoftwareMap.put("yaml", yamlContent);
            deploySoftwareMap.put("roleIds", roleIds);
            deploySoftwareMap.put("clusterId", context.get("clusterId"));
            context.put("serviceDeployRequest", JacksonUtils.toJson(deploySoftwareMap));
            //构建路由配置接口入参
            String softwareCode = softwareProductListResponse.getSvcCode();
            Map<String, BuildInfoServiceModel> services = buildInfoYaml.getServices();
            Map<String, BuildInfoServiceModel> serviceCodeBuildMap = services.values().stream()
                    .collect(Collectors.toMap(BuildInfoServiceModel::getServiceCode, v -> v));
            BuildInfoServiceModel buildInfoServiceModel = serviceCodeBuildMap.get(softwareCode);
            log.info("构建路由配置接口入参,软件编码:{},buildInfo.services:{}", softwareCode, JacksonUtils.toJson(buildInfoServiceModel));
            RouteAddModel routeAddModel = RouteAddModel.buildDefaultRouteAddModel();
            routeAddModel.setRouteName(buildInfoServiceModel.getDeployName() + buildInfoYaml.getBuildVersion());
            routeAddModel.setServiceCode(buildInfoServiceModel.getServiceCode());
            routeAddModel.setServiceName(buildInfoServiceModel.getServiceName());
            List<RouteAddModel.RouteUrisDTO> routeUris = Lists.newArrayList();
            routeAddModel.setRouteUris(routeUris);
            log.info("构建路由配置接口入参开始Yaml读取svc.ymal:{}", outputYamlFilePath);
            Map<String, Object> svmYamlMap = yaml.load(yamlContent);
            RouteAddModel.RouteUrisDTO routeUri;
            if (CollUtil.isNotEmpty(svmYamlMap)) {
                if (svmYamlMap.get("metadata") instanceof Map) {
                    Map<String, Object> metadata = (Map<String, Object>) svmYamlMap.get("metadata");
                    String name = String.valueOf(metadata.get("name"));
                    String namespace = String.valueOf(metadata.get("namespace"));
                    System.out.println("Name: " + name + " ,namespace: " + namespace);
                    if (svmYamlMap.get("spec") instanceof Map) {
                        Map<String, Object> spec = (Map<String, Object>) svmYamlMap.get("spec");
                        if (spec.get("ports") instanceof List) {
                            List<Map<String, Object>> ports = (List<Map<String, Object>>) spec.get("ports");
                            for (Map<String, Object> port : ports) {
                                routeUri = new RouteAddModel.RouteUrisDTO();
                                routeUris.add(routeUri);
                                String portsName = String.valueOf(port.get("name"));
                                Integer portNumber = (Integer) port.get("port");
                                String protocol = String.valueOf(port.get("protocol"));
                                String targetPort = String.valueOf(port.get("targetPort"));
                                System.out.println("Port Name: " + portsName + ", Port Number: " + portNumber + ", Protocol: " + protocol + ", Target Port: " + targetPort);
                                routeUri.setVersion("V1");
                                routeUri.setRouteUri("http://" + name + "." + namespace + ":" + targetPort);
                                routeUri.setWeight(1);
                                RouteAddModel.RouteUrisDTO.RoutePredicatesDTO defaultRoutePredicates = RouteAddModel.buildDefaultRoutePredicatesDTO();
                                routeUri.setRoutePredicates(CollUtil.newArrayList(defaultRoutePredicates));
                            }
                        }
                    }
                }
            }
            //保存路由配置接口入参
            context.put("gateWayRouteAddRequest", JacksonUtils.toJson(routeAddModel));
        } catch (IOException e) {
            log.error("构建路由配置接口入参,异常:{}", e.getMessage(), e);
        }
    }

    /**
     * 解析buildInfo.yaml
     *
     * @param buildPrefixSavePath
     * @return
     */
    private BuildInfoModel buildBuildInfoYaml(String buildPrefixSavePath) {
        Path path = Paths.get(buildPrefixSavePath);
        if (!Files.exists(path)) {
            log.error("[buildInfo.yaml]构建表的父级文件路径不存在:{}", buildPrefixSavePath);
        } else {
            Path buildInfoPath = path.resolve("buildInfo.yaml");
            Yaml yaml = new Yaml();
            try (InputStream inputStream = Files.newInputStream(buildInfoPath)) {
                Map<String, Object> load = yaml.load(inputStream);
                String json = JacksonUtils.toJson(load);
                log.info("解析buildInfo.yaml内容:{}", json);
                return JacksonUtils.toObj(json, BuildInfoModel.class);
            } catch (IOException e) {
                // 处理文件读取异常
                log.error("Failed to read YAML file", e);
            }
        }
        return new BuildInfoModel();
    }

    /**
     * 构建应用安装入参
     *
     * @param softwareProductListResponse
     * @param context
     */
    private void buildAppInstallRequestParam(SoftwareProductListResponse softwareProductListResponse, SoftwareDeployContext context) {
        /**
         * ossPath	应用路径	字符串	N	应用构建后在oss中存储的路径
         * serviceGateway	服务网关地址	字符串	N	服务网关地址
         * nodeCode	节点编码	字符串	N	多中心节点编码
         */
        Map<String, String> appInstallRequestMap = new HashMap<>();
        appInstallRequestMap.put("ossPath", context.get("softWarePath"));
        appInstallRequestMap.put("serviceGateway", "");
        appInstallRequestMap.put("nodeCode", softwareProductListResponse.getCenterCode());
        context.put("appInstallRequest", JacksonUtils.toJson(appInstallRequestMap));
    }

    /**
     * 构建无状态服务部署 入参
     *
     * @param softwareProductListResponse
     * @param context
     */
    private void buildDeployCreateRequestParam(SoftwareProductListResponse softwareProductListResponse, SoftwareDeployContext context, List<Integer> roleIds, List<String> clusterIds) {
/**
 * clusterId	软件名	字符串	200字符	非空(？？ 这个是名称还是deployId的字符串啊？)
 * namespace	名称空间	字符串	20字符	非空
 * yaml	部署yaml	字符串	4000字符	非空
 * roleIds	角色Id集合	字符串	100字符	非空
 * serviceCode	服务目录编码	字符串	N	服务目录编码
 */
        String buildPackSavePath = softwareProductListResponse.getBuildPackSavePath();
        //遍历文件路径，获取-deploy文件
        Path path = Paths.get(buildPackSavePath);
        if (!Files.exists(path)) {
            log.error("[构建无状态服务部署入参]文件路径不存在:{}", buildPackSavePath);
        } else {
            File dir = new File(path.toString());
            //用原始备份文件，不用修改的（原样传）
            File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith("-deploy.yaml"));
            if (jsonFiles != null) {
                if (jsonFiles.length > 1) {
                    log.warn("[动态修改yaml文件]目录：{}存在多个deploy.yaml", buildPackSavePath);
                }
                File deployYamlFile = jsonFiles[0];
                Path outputYamlFilePath = deployYamlFile.toPath();
                Yaml yaml = new Yaml();
                Map<String, Object> load;
                try (InputStream inputStream = Files.newInputStream(outputYamlFilePath)) {
                    Map<String, Object> deploySoftwareMap = new HashMap<>();
                    load = yaml.load(inputStream);
                    //metadata: annotations: k8s.unitechs.com/clusterId: "360403983c98ecaf70081ecde665b4da"
                    if (load != null && load.get("metadata") instanceof Map) {
                        Map<String, Object> metadata = (Map<String, Object>) load.get("metadata");
                        if (metadata.get("annotations") instanceof Map) {
                            Map<String, Object> annotations = (Map<String, Object>) metadata.get("annotations");
                            String clusterId = String.valueOf(annotations.get("k8s.unitechs.com/clusterId"));
                            context.put("clusterId", clusterId);
                            clusterIds.add(clusterId);
                            deploySoftwareMap.put("clusterId", clusterId);
                        }
                    }
                    byte[] bytes = Files.readAllBytes(outputYamlFilePath);
                    String yamlContent = new String(bytes, StandardCharsets.UTF_8);
                    deploySoftwareMap.put("namespace", softwareProductListResponse.getNamespace());
                    deploySoftwareMap.put("yaml", yamlContent);
                    deploySoftwareMap.put("roleIds", roleIds);
                    context.put("deployCreateRequest", JacksonUtils.toJson(deploySoftwareMap));
                } catch (IOException e) {
                    // 处理文件读取异常
                    log.error("构建无状态服务部署接口入参,异常:{}", e.getMessage());
                }
            } else {
                log.info("构建无状态服务部署接口入参,路径：{}无deploy.yaml:", buildPackSavePath);
            }

        }
    }

    /**
     * 镜像推送入参
     *
     * @param softwareProductListResponse
     * @param context
     */
    private static void buildImagePushRequestParam(BuildInfoModel buildInfoYaml, SoftwareProductListResponse softwareProductListResponse, SoftwareDeployContext context) {
        //imageName	镜像名称	字符串	500字符	非空
        //centerCode	协同中心编码	字符串	200字符	可非空
        //centerName	协同中心名称	字符串	200字符	非空
        //softwareCode	软件编码	字符串	20字符	非空
        //softwareVersion	软件版本	字符串	20字符	非空
        //deployName	部署名称	字符串	100字符	非空
        Map<String, Object> imagePushRequestMap = new HashMap<>();
//        imagePushRequestMap.put("imageName", softwareProductListResponse.getImageName());
        imagePushRequestMap.put("centerCode", softwareProductListResponse.getCenterCode());
        imagePushRequestMap.put("centerName", softwareProductListResponse.getCenterName());
        imagePushRequestMap.put("softwareCode", buildInfoYaml.getSoftwareCode());
        imagePushRequestMap.put("softwareVersion", buildInfoYaml.getBuildVersion());
        Map<String, BuildInfoServiceModel> buildInfoYamlServices = buildInfoYaml.getServices();
        String softwareCode = softwareProductListResponse.getSvcCode();
        if (CollUtil.isNotEmpty(buildInfoYamlServices)) {
            Map<String, BuildInfoServiceModel> serviceCodeBuildMap = buildInfoYamlServices.values().stream()
                    .collect(Collectors.toMap(BuildInfoServiceModel::getServiceCode, v -> v));
            BuildInfoServiceModel buildInfoServiceModel = serviceCodeBuildMap.get(softwareCode);
            log.info("构建镜像推送入参接口入参,软件编码：{},BuildInfo:{}", softwareCode, JacksonUtils.toJson(buildInfoServiceModel));
            imagePushRequestMap.put("imageTarNameSet", buildInfoServiceModel.getImageTarNames());
            imagePushRequestMap.put("deployName", buildInfoServiceModel.getDeployName());
        } else {
            imagePushRequestMap.put("imageTarNameSet", Collections.emptyList());
            imagePushRequestMap.put("deployName", softwareProductListResponse.getSvcCode());
        }
        context.put("imagePushRequest", JacksonUtils.toJson(imagePushRequestMap));
    }

    @Override
    public ApiResponse<List<SoftwareAndAppInfoResponse>> selectProduct() {
//        return new ApiResponse<>("200", "查询成功", softwareDeploySoftwareInfoMapper.selectProduct());
        SoftwareCatalog softwareCatalog = new SoftwareCatalog();
        //0 已通过  1未通过
        softwareCatalog.setSoftwareStatus("0");
        List<SoftwareCatalog> softwareCatalogs = softwareCatalogMapper.selectSoftwareCatalog(softwareCatalog);
        if (CollUtil.isEmpty(softwareCatalogs)) {
            return new ApiResponse<>("200", "查询成功", Collections.emptyList());
        }
        //转换为跟前端定义好的结构
        List<SoftwareAndAppInfoResponse> softwareAndAppInfoResponses = softwareCatalogs.stream()
                .map(this::convertSoftCatalogToResponse)
                .collect(Collectors.toList());


        return new ApiResponse<>("200", "查询成功", softwareAndAppInfoResponses);
    }

    private SoftwareAndAppInfoResponse convertSoftCatalogToResponse(SoftwareCatalog k) {
        SoftwareAndAppInfoResponse softwareAndAppInfoResponse = new SoftwareAndAppInfoResponse();
        //属性赋值
        BeanUtils.copyProperties(k, softwareAndAppInfoResponse);
        //构造唯一主键，联合组件生成long
        String key = k.getCenterCode() + "_" + k.getSoftwareCode();
        softwareAndAppInfoResponse.setId(SnowflakeIdGenerator.generateUniqueId(key));
        //中心名称暂填充为中心编码
        softwareAndAppInfoResponse.setCenterName(k.getCenterCode());
        //软件版本填充为软件安装版本
        softwareAndAppInfoResponse.setSoftwareVersion(k.getSoftwareInstallVersion());
        //对下挂应用转对象
        String serviceList = k.getServiceList();
        if (StringUtils.isNotBlank(serviceList)) {
            //List<SoftwareDeploySoftwareAppInfo> softwareDefinitionRels = JSON.parseArray(serviceList, SoftwareDeploySoftwareAppInfo.class);
            List<SoftwareDeploySoftwareAppInfo> softwareDefinitionRels = convert(serviceList);
            softwareAndAppInfoResponse.setSoftwareDefinitionRels(softwareDefinitionRels);
        } else {
            softwareAndAppInfoResponse.setSoftwareDefinitionRels(new ArrayList<>());
        }
        return softwareAndAppInfoResponse;
    }

    /**
     * 将 JSON 字符串转换为 List<SoftwareDeploySoftwareAppInfo>集合
     *
     * @param jsonString JSON 格式的字符串
     * @return 转换后的集合
     */
    public static List<SoftwareDeploySoftwareAppInfo> convert(String jsonString) {
        // 解决 LocalDateTime 类型序列化问题
        JSON.DEFFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
        // 使用 TypeReference 指定泛型类型
        List<SoftwareDeploySoftwareAppInfo> resultList = JSON.parseObject(
                jsonString,
                new TypeReference<List<SoftwareDeploySoftwareAppInfo>>() {
                }
        );


        return resultList;
    }

    @Override
    public ApiResponse<Void> export(QueryDeploySoftwareInfoDto queryDeploySoftwareInfo, HttpServletResponse response) {
        // 设置正确的 Content-Type
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");

        try (ExcelWriter excelWriter = EasyExcel.write(response.getOutputStream()).build()) {
            // 构建带时间戳的文件名并进行 URL 编码
            String baseFileName = "软件部署安装列表-" + System.currentTimeMillis();
            String encodedFileName = URLEncoder.encode(baseFileName, "UTF-8").replaceAll("\\+", "%20");
            String fileNameWithExt = encodedFileName + ".xlsx";
            response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileNameWithExt);

            WriteSheet writeSheet = EasyExcel.writerSheet("软件部署安装记录")
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .head(SoftwareDeploySoftwareInfo.class)
                    .build();

            List<SoftwareDeploySoftwareInfo> softwareDeploySoftwareInfos = softwareDeploySoftwareInfoMapper.selectAll(queryDeploySoftwareInfo);
            excelWriter.write(softwareDeploySoftwareInfos, writeSheet);
        } catch (Exception e) {
            log.error("导出失败: {}", e.getMessage(), e);
            return new ApiResponse<>("500", "导出失败", null);
        }
        return new ApiResponse<>("200", "导出成功", null);
    }

    /**
     * 判断是否为部署文件
     */
    private static boolean isDeploymentFile(String entryName) {
        // 匹配 *-deploy.yaml 文件
        return entryName.toLowerCase().endsWith("-deploy.yaml")
                && entryName.split("/").length > 1; // 确保在子目录中
    }


    public static void main(String[] args) throws IOException {
        String prifexPath = "D:\\work\\项目文档\\2025\\CBG-XW多中心协同\\真实的构建包\\hyy-test";
//        Path path = Paths.get(prifexPath).resolve("buildInfo.yaml");
        //Path path = Paths.get(prifexPath).resolve("hyy-bpce-ds-server").resolve("hyy-bpce-ds-server-svc.yaml");

        Path path = Paths.get(prifexPath);
        if (!Files.exists(path)) {
            log.error("[buildInfo.yaml]构建表的父级文件路径不存在:{}");
        } else {
            Path buildInfoPath = path.resolve("buildInfo.yaml");
            Yaml yaml = new Yaml();
            try (InputStream inputStream = Files.newInputStream(buildInfoPath)) {
                Map<String, Object> load = yaml.load(inputStream);
                String json = JacksonUtils.toJson(load);
                log.info("解析buildInfo.yaml内容:{}", json);
                BuildInfoModel buildInfoModel = JacksonUtils.toObj(json, BuildInfoModel.class);

                Map<String, BuildInfoServiceModel> services = buildInfoModel.getServices();
                Map<String, BuildInfoServiceModel> serviceCodeBuildMap = services.values().stream()
                        .collect(Collectors.toMap(BuildInfoServiceModel::getServiceCode, v -> v));

                System.out.println(services + "" + serviceCodeBuildMap);

//                //获取services对象->对应软件编码-》获取imageTarNames
//                List<String> imageTarNames = Optional.ofNullable(buildInfoModel.getServices())
//                        .map(services -> services.get("hyy-bpce-runtime"))
//                        .map(BuildInfoServiceModel::getImageTarNames)
//                        .filter(CollUtil::isNotEmpty)
//                        .orElse(Collections.emptyList());
//                System.out.println("ssss"+imageTarNames);

            } catch (IOException e) {
                // 处理文件读取异常
                log.error("Failed to read YAML file", e);
            }
        }


        String buildPackSavePath = "D:\\work\\项目文档\\2025\\CBG-XW多中心协同\\真实的构建包\\hyy-test\\hyy-bpce-ds-server";
        //Path path = Paths.get(buildPackSavePath);
        if (!Files.exists(path)) {
            log.error("[动态修改yaml文件]构建表的父级文件路径不存在:{}", buildPackSavePath);
        } else {
            File dir = new File(path.toString());
            File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith("-deploy.yaml"));
            if (jsonFiles != null) {
                if (jsonFiles.length > 0) {
                    log.warn("[动态修改yaml文件]目录存在多个deploy.yaml:{}", buildPackSavePath);
                }
                File deployYamlFile = jsonFiles[0];
                Path inputYamlFilePath = deployYamlFile.toPath();
                Path bakYamlFilePath = Paths.get(deployYamlFile.getPath() + ".bak");
                //备份文件
                Files.copy(inputYamlFilePath, bakYamlFilePath);

                // 写入文件
                Files.write(inputYamlFilePath, "这是新内容".getBytes());
                //读取对应系统的部署yaml文件
                //Path outputYamlFilePath = Paths.get(buildPackSavePath + File.separator + sysCode + "-deploy-" + deployId + ".yaml");
                List<KubernetesConfigEditor.YamlOperation> operations = new ArrayList<>();
                //通过反射构建yaml路径对应的值对象
                //builderYamlOperations(operations, softwareAppInfo);
                //KubernetesConfigEditor.updateDeployYaml(operations, bakYamlFilePath, inputYamlFilePath);
            } else {
                log.error("[动态修改yaml文件]文件路径:{}下找到-deploy.yaml文件", path);
            }
        }


        Yaml yaml = new Yaml();
        //metadata:
        //  annotations:
        //    k8s.unitechs.com/clusterId: "360403983c98ecaf70081ecde665b4da"

        Map<String, Object> load;
        try (InputStream inputStream = Files.newInputStream(path)) {
            load = yaml.load(inputStream);
            //metadata:
            //  annotations:
            //    k8s.unitechs.com/clusterId: "360403983c98ecaf70081ecde665b4da"

//            System.out.println(JacksonUtils.toJson(load));
//            BuildInfoModel buildInfoYaml = JacksonUtils.toObj(JacksonUtils.toJson(load), BuildInfoModel.class);
//            String deployName = Optional.ofNullable(buildInfoYaml.getServices())
//                    .map(services -> services.get("hyy-bpce-runtime"))
//                    .map(BuildInfoServiceModel::getServiceName)
//                    .orElse("hyy-bpce-runtime");
//            System.out.println(">>>>>>>>>>"+deployName);

            if (load != null) {
                if (load.get("metadata") instanceof Map) {
                    Map<String, Object> metadata = (Map<String, Object>) load.get("metadata");
                    String name = String.valueOf(metadata.get("name"));
                    String namespace = String.valueOf(metadata.get("namespace"));
                    System.out.println("Name: " + name + " namespace: " + namespace);
                }
                if (load.get("spec") instanceof Map) {
                    Map<String, Object> spec = (Map<String, Object>) load.get("spec");
                    if (spec.get("ports") instanceof List) {
                        List<Map<String, Object>> ports = (List<Map<String, Object>>) spec.get("ports");
                        for (Map<String, Object> port : ports) {
                            String name = String.valueOf(port.get("name"));
                            Integer portNumber = (Integer) port.get("port");
                            String protocol = String.valueOf(port.get("protocol"));
                            String targetPort = String.valueOf(port.get("targetPort"));
                            System.out.println("Port Name: " + name + ", Port Number: " + portNumber + ", Protocol: " + protocol + ", Target Port: " + targetPort);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // 处理文件读取异常
            log.error("Failed to read YAML file", e);
        }
    }

}
