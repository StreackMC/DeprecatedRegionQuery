package com.github.streackmc.regionquery;

import com.github.streackmc.StreackLib.StreackLib;
import com.github.streackmc.StreackLib.utils.HTTPServer;
import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

/**
 * RegionQuery —— 轻量级区域文件InhabitedTime查询插件（修正版 + 详细日志）
 * <p>
 * 通过HTTP接口接收 x、z 坐标，正确解析 .mca 区域文件中对应区块的 InhabitedTime，
 * 并与配置的阈值比较，返回 {destroy: true/false} 的JSON。
 * 同时输出完整的解析过程日志（坐标、文件、中间结果、最终判定）。
 * </p>
 */
public class RegionQuery extends JavaPlugin {

    private Logger logger;

    private String endpoint;
    private String worldContainer;
    private long inhabitedTimeThreshold;

    @Override
    public void onEnable() {
        this.logger = getLogger();

        if (!Bukkit.getPluginManager().isPluginEnabled("StreackLib")) {
            logger.severe("StreackLib 未启用，插件无法继续运行。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        reloadConfigParams();

        HTTPServer httpServer = StreackLib.getHttpServer();
        if (httpServer == null) {
            logger.severe("StreackLib 内建 HTTP 服务器未启动，插件无法继续运行。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            httpServer.registerHandler(endpoint, session -> {
                if (!fi.iki.elonen.NanoHTTPD.Method.GET.equals(session.getMethod())) {
                    return newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                            "text/plain",
                            "Method GET Allowed Only."
                    );
                }

                try {
                    Map<String, String> params = session.getParms();
                    String xStr = params.get("x");
                    String zStr = params.get("z");

                    if (xStr == null || zStr == null) {
                        return newFixedLengthResponse(
                                fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                                "application/json",
                                "{\"error\": \"Missing x or z parameter\"}"
                        );
                    }

                    int x, z;
                    try {
                        x = Integer.parseInt(xStr);
                        z = Integer.parseInt(zStr);
                    } catch (NumberFormatException e) {
                        return newFixedLengthResponse(
                                fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                                "application/json",
                                "{\"error\": \"x and z must be integers\"}"
                        );
                    }

                    // 坐标转换
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    int regionX = chunkX >> 5;
                    int regionZ = chunkZ >> 5;

                    String regionFileName = "r." + regionX + "." + regionZ + ".mca";
                    File serverRoot = Bukkit.getWorldContainer();
                    File regionDir = new File(serverRoot, worldContainer + File.separator + "region");
                    File regionFile = new File(regionDir, regionFileName);

                    // 详细日志：输入坐标
                    logger.info("===== 解析开始 =====");
                    logger.info("输入坐标: x=" + x + ", z=" + z);
                    logger.info("区块坐标: chunkX=" + chunkX + ", chunkZ=" + chunkZ);
                    logger.info("区域坐标: regionX=" + regionX + ", regionZ=" + regionZ);
                    logger.info("区域文件路径: " + regionFile.getAbsolutePath());

                    boolean destroy;
                    long inhabitedTime = -1;

                    if (!regionFile.exists()) {
                        logger.info("区域文件不存在 → 判定 destroy = true");
                        destroy = true;
                    } else {
                        inhabitedTime = readInhabitedTimeFromRegion(regionFile, chunkX, chunkZ, logger);
                        if (inhabitedTime == -1) {
                            logger.info("区块不存在或读取失败 → 判定 destroy = true");
                            destroy = true;
                        } else {
                            logger.info("解析得到 InhabitedTime: " + inhabitedTime + " (阈值: " + inhabitedTimeThreshold + ")");
                            destroy = inhabitedTime <= inhabitedTimeThreshold;
                            logger.info("比较结果: " + inhabitedTime + " <= " + inhabitedTimeThreshold + " ? " + destroy);
                        }
                    }

                    logger.info("最终返回值: destroy=" + destroy);
                    logger.info("===== 解析结束 =====");

                    String jsonResponse = String.format("{\"destroy\": %s}", destroy);
                    return newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                            "application/json",
                            jsonResponse
                    );

                } catch (Exception e) {
                    logger.log(Level.SEVERE, "处理请求时发生异常", e);
                    return newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "application/json",
                            "{\"error\": \"Internal server error\"}"
                    );
                }
            });

            logger.info("RegionQuery 已注册端点: " + endpoint);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "注册 HTTP 处理器失败: " + endpoint, e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        HTTPServer httpServer = StreackLib.getHttpServer();
        if (httpServer != null && endpoint != null) {
            httpServer.removeHandler(endpoint);
        }
        logger.info("RegionQuery 已卸载。");
    }

    private void reloadConfigParams() {
        reloadConfig();
        endpoint = getConfig().getString("endpoint", "/api/region/query");
        worldContainer = getConfig().getString("world-container", "world");
        inhabitedTimeThreshold = getConfig().getLong("inhabited-time-threshold", 3600000L);
        logger.info("配置已加载：endpoint=" + endpoint +
                ", world-container=" + worldContainer +
                ", inhabited-time-threshold=" + inhabitedTimeThreshold);
    }

    /**
     * 从 .mca 区域文件中读取指定区块的 InhabitedTime。
     *
     * @param regionFile 区域文件对象
     * @param chunkX     区块 X 坐标（全局坐标）
     * @param chunkZ     区块 Z 坐标（全局坐标）
     * @param logger     日志记录器（用于输出内部解析细节）
     * @return 区块的 InhabitedTime，若区块不存在或读取失败则返回 -1
     */
    private long readInhabitedTimeFromRegion(File regionFile, int chunkX, int chunkZ, Logger logger) {
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        int index = localZ * 32 + localX;

        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            // 读取 Chunk Offset Table
            raf.seek(index * 4);
            byte[] offsetBytes = new byte[4];
            raf.read(offsetBytes);
            int offsetSector = ByteBuffer.wrap(offsetBytes).order(ByteOrder.BIG_ENDIAN).getInt();

            if (offsetSector == 0) {
                logger.info("区块局部索引 " + index + " 在 Offset Table 中为 0 → 区块未生成");
                return -1;
            }

            int sectorCount = offsetSector & 0xFF;
            int chunkOffset = (offsetSector >> 8) * 4096;
            logger.fine("区块偏移扇区: " + offsetSector + ", 扇区数: " + sectorCount + ", 字节偏移: " + chunkOffset);

            // 读取区块头
            raf.seek(chunkOffset);
            byte[] lengthBytes = new byte[4];
            raf.read(lengthBytes);
            int dataLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getInt();

            if (dataLength <= 0 || dataLength > sectorCount * 4096) {
                logger.warning("无效的数据长度: " + dataLength);
                return -1;
            }

            byte compressionType = raf.readByte();
            if (compressionType != 1 && compressionType != 2) {
                logger.warning("不支持的压缩类型: " + compressionType);
                return -1;
            }

            // 读取压缩数据
            byte[] compressed = new byte[dataLength - 1];
            raf.read(compressed);

            // 解压缩
            byte[] decompressed;
            if (compressionType == 1) {
                try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                     java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(bais)) {
                    decompressed = gzip.readAllBytes();
                }
            } else {
                Inflater inflater = new Inflater();
                inflater.setInput(compressed);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                while (!inflater.finished()) {
                    int len = inflater.inflate(buffer);
                    baos.write(buffer, 0, len);
                }
                inflater.end();
                decompressed = baos.toByteArray();
            }

            // 解析 NBT
            NBTDeserializer deserializer = new NBTDeserializer(true, false);
            NamedTag namedTag = deserializer.fromStream(new ByteArrayInputStream(decompressed));
            if (!(namedTag.getTag() instanceof CompoundTag)) {
                logger.warning("NBT 根标签不是 CompoundTag");
                return -1;
            }
            CompoundTag levelTag = (CompoundTag) namedTag.getTag();
            if (!levelTag.containsKey("InhabitedTime")) {
                logger.warning("区块 NBT 中未找到 InhabitedTime 字段");
                return -1;
            }
            long inhabitedTime = levelTag.getLong("InhabitedTime");
            logger.fine("成功读取 InhabitedTime = " + inhabitedTime);
            return inhabitedTime;
        } catch (IOException | DataFormatException e) {
            logger.log(Level.WARNING, "读取/解析区域文件时发生 I/O 或数据格式异常: " + regionFile.getAbsolutePath(), e);
            return -1;
        }
    }
}