package com.voxeltree.harvester;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.voxeltree.harvester.ingest.IngestAllCommand;
import com.voxeltree.harvester.ingest.IngestPayload;
import com.voxeltree.harvester.noise.NoiseDumperCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class DataHarvesterMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("dataharvester");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitialize() {
        LOGGER.info("[DataHarvester] Registering server-side commands...");
        PayloadTypeRegistry.playS2C().register(IngestPayload.TYPE, IngestPayload.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            NoiseDumperCommand.register(dispatcher);
            LOGGER.info("[DataHarvester] /dumpnoise command registered.");
            IngestAllCommand.register(dispatcher);
            LOGGER.info("[DataHarvester] /ingestall command registered.");
        });
        ServerLifecycleEvents.SERVER_STARTED.register(DataHarvesterMod::onServerStarted);
    }

    private static void onServerStarted(MinecraftServer server) {
        Path marker = Path.of("config", "oracle_mode.json");
        boolean isOracle = false;
        String levelName = null;
        try {
            try {
                Method m = server.getClass().getMethod("getSaveProperties");
                Object props = m.invoke(server);
                Method lm = props.getClass().getMethod("getLevelName");
                levelName = (String) lm.invoke(props);
            } catch (Exception ignored) {
                try {
                    Method wp = server.getClass().getMethod("getWorldPath", net.minecraft.world.level.storage.LevelResource.class);
                    Path p = (Path) wp.invoke(server, net.minecraft.world.level.storage.LevelResource.ROOT);
                    levelName = p.getFileName().toString();
                } catch (Exception ignored2) {}
            }
            if (levelName != null && levelName.startsWith("oracle_")) isOracle = true;
        } catch (Exception ignored) {}

        if (!isOracle && Files.exists(marker)) {
            try {
                String json = Files.readString(marker);
                if (json.contains("oracle_end_chorus")) isOracle = true;
            } catch (IOException ignored) {}
        }
        if (!isOracle) return;

        LOGGER.info("[DataHarvester][Oracle] Detected oracle mode (level='{}'), applying hard freeze before first tick...", levelName);

        boolean frozen = false;
        int tickCount = 0;
        int randomTickSpeed = -1;
        String error = null;
        StringBuilder debug = new StringBuilder();

        try {
            try {
                int res = executeServerCommand(server, "tick freeze");
                LOGGER.info("[DataHarvester][Oracle] 'tick freeze' result {}", res);
                frozen = (res >= 0);
                if (!frozen) debug.append(" tick freeze returned ").append(res).append(";");
                try { int q = executeServerCommand(server, "tick query"); debug.append(" tick query ").append(q).append(";"); } catch(Exception ignored){}
            } catch(Exception e){
                debug.append(" tick freeze failed ").append(e).append(";");
                LOGGER.error("[DataHarvester] tick freeze failed", e);
                throw e;
            }
            try {
                int res = executeServerCommand(server, "gamerule randomTickSpeed 0");
                LOGGER.info("[DataHarvester][Oracle] gamerule result {}", res);
                randomTickSpeed = 0;
            } catch(Exception e){
                debug.append(" gamerule failed ").append(e).append(";");
                error = "gamerule failed: " + e + debug;
                LOGGER.error("[DataHarvester] gamerule failed", e);
            }
            try {
                for(Method m: server.getClass().getMethods()) if(m.getParameterCount()==0 && m.getReturnType()==int.class && m.getName().toLowerCase().contains("tick")){ tickCount=(int)m.invoke(server); debug.append(" tickCount via ").append(m.getName()).append("=").append(tickCount).append(";"); break; }
                if(tickCount==0) debug.append(" tickCount default 0;");
            } catch(Exception e){ debug.append(" tick count err ").append(e).append(";"); }
            if (!frozen) error = (error==null? "tick freeze not confirmed" + debug : error + "; tick freeze not confirmed" + debug);
        } catch(Exception e){
            String msg = e.toString() + debug;
            error = (error==null? msg : error + "; " + msg);
            LOGGER.error("[DataHarvester] Failed freeze {}", msg, e);
        }

        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("simulationFrozenBeforeFirstTick", frozen && error==null);
        receipt.put("randomTickSpeed", randomTickSpeed);
        receipt.put("tickCountAtFreeze", tickCount);
        receipt.put("levelName", levelName);
        receipt.put("timestamp", Instant.now().toString());
        if(error!=null) receipt.put("error", error);
        receipt.put("frozen", frozen);
        if(debug.length()>0) receipt.put("debug", debug.toString());

        Path receiptPath = Path.of("config","oracle_startup_receipt.json");
        Path alt = Path.of("oracle_startup_receipt.json");
        try{
            Files.createDirectories(receiptPath.getParent());
            String json=GSON.toJson(receipt);
            Files.writeString(receiptPath, json);
            Files.writeString(alt, json);
            LOGGER.info("[DataHarvester] Wrote receipt {}: {}", receiptPath.toAbsolutePath(), json);
        }catch(IOException e){ LOGGER.error("[DataHarvester] Failed to write receipt", e); }

        if(!frozen || randomTickSpeed!=0) LOGGER.error("[DataHarvester] Invariant FAILED frozen={} rts={} err={} debug={}", frozen, randomTickSpeed, error, debug);
        else LOGGER.info("[DataHarvester] Invariant OK frozen before first tick rts=0 tickCount={}", tickCount);
    }

    private static Object findCommandSourceStack(MinecraftServer server) throws Exception {
        String[] names = {"createCommandSourceStack","getCommandSourceStack","createCommandSource","getCommandSource","method_3739","method_3176","method_3738","method_3790","method_3734","method_3760"};
        for(String n: names){
            try{ Method m = server.getClass().getMethod(n); Object o=m.invoke(server); if(o!=null){ LOGGER.info("[DataHarvester] Found CommandSourceStack via {}", n); return o; } }catch(Exception ignored){}
            try{ Method m = server.getClass().getDeclaredMethod(n); m.setAccessible(true); Object o=m.invoke(server); if(o!=null){ LOGGER.info("[DataHarvester] Found via declared {}", n); return o; } }catch(Exception ignored){}
        }
        for(Method m: server.getClass().getMethods()){
            if(m.getParameterCount()!=0) continue;
            Class<?> ret=m.getReturnType();
            if(ret==void.class || ret.isPrimitive() || ret==String.class) continue;
            boolean hasSup=false;
            for(Method rm: ret.getMethods()) if(rm.getName().toLowerCase().contains("suppressed") && rm.getParameterCount()==0) hasSup=true;
            boolean sameType=false;
            for(Method rm: ret.getMethods()) if(rm.getParameterCount()==0 && rm.getReturnType()==ret) sameType=true;
            if(!hasSup && !sameType) continue;
            try{
                Object o=m.invoke(server);
                if(o!=null){
                    LOGGER.info("[DataHarvester] Found via return-type {} -> {} hasSup={} sameType={}", m.getName(), ret.getName(), hasSup, sameType);
                    return o;
                }
            }catch(Exception ignored){}
        }
        throw new NoSuchMethodException("createCommandSourceStack not found " + Arrays.toString(server.getClass().getMethods()));
    }

    private static int executeServerCommand(MinecraftServer server, String command) throws Exception {
        Object sourceStack = findCommandSourceStack(server);
        try{
            Method wso=null;
            for(Method m: sourceStack.getClass().getMethods()){
                if(m.getParameterCount()!=0) continue;
                if(m.getReturnType()==sourceStack.getClass()){
                    String mn=m.getName().toLowerCase();
                    if(mn.contains("suppressed")) { wso=m; break; }
                }
            }
            if(wso==null) for(Method m: sourceStack.getClass().getMethods()) if(m.getParameterCount()==0 && m.getReturnType()==sourceStack.getClass()) { wso=m; break; }
            if(wso!=null) {
                Object next = wso.invoke(sourceStack);
                if(next!=null) sourceStack=next;
                LOGGER.info("[DataHarvester] Applied withSuppressedOutput via {}", wso.getName());
            } else {
                try{ sourceStack=sourceStack.getClass().getMethod("withSuppressedOutput").invoke(sourceStack); } catch(Exception ignored){}
            }
        }catch(Exception e){ LOGGER.info("[DataHarvester] withSuppressedOutput failed {}", e.toString()); }
        try{
            boolean applied=false;
            for(Method m: sourceStack.getClass().getMethods()){
                String mn=m.getName().toLowerCase();
                if((mn.contains("permission")||mn.contains("level")) && m.getParameterCount()==1){
                    Class<?> pt=m.getParameterTypes()[0];
                    try{
                        if(pt==int.class){ sourceStack=m.invoke(sourceStack,4); LOGGER.info("[DataHarvester] Elevated via {}(4)", m.getName()); applied=true; break; }
                        else{
                            Object[] consts=(Object[]) pt.getMethod("values").invoke(null);
                            if(consts.length>0){
                                Object chosen=null;
                                for(Object c: consts) if(c.toString().contains("ALL")||c.toString().toLowerCase().contains("admin")) chosen=c;
                                if(chosen==null) chosen=consts[consts.length-1];
                                sourceStack=m.invoke(sourceStack,chosen); LOGGER.info("[DataHarvester] Elevated via {}({})", m.getName(), chosen); applied=true; break;
                            }
                        }
                    }catch(Exception ignored){}
                }
            }
            if(!applied) LOGGER.info("[DataHarvester] No permission elevation applied");
        }catch(Exception e){ LOGGER.info("[DataHarvester] permission elevation failed {}", e.toString()); }

        Object commandManager=null;
        // Find command manager via signature: 0 args, return type has method (Object, String) -> int with 2 params second String
        for(Method m: server.getClass().getMethods()){
            if(m.getParameterCount()!=0) continue;
            Class<?> ret=m.getReturnType();
            if(ret==void.class || ret.isPrimitive() || ret==String.class) continue;
            boolean isCmdMgr=false;
            for(Method rm: ret.getMethods()){
                if(rm.getParameterCount()==2 && rm.getParameterTypes()[1]==String.class){
                    // Check first param is not String, and return is int or void
                    if(rm.getParameterTypes()[0]==String.class) continue;
                    if(rm.getReturnType()==int.class || rm.getReturnType()==void.class || rm.getReturnType()==Integer.class){
                        isCmdMgr=true; break;
                    }
                }
            }
            if(!isCmdMgr) continue;
            try{
                Object cand=m.invoke(server);
                if(cand!=null){
                    LOGGER.info("[DataHarvester] Found command manager via {} -> {}", m.getName(), ret.getName());
                    commandManager=cand; break;
                }
            }catch(Exception e){ LOGGER.info("[DataHarvester] invoke {} failed {}", m.getName(), e.toString()); }
        }
        if(commandManager==null){
            for(Method m: server.getClass().getDeclaredMethods()){
                if(m.getParameterCount()!=0) continue;
                Class<?> ret=m.getReturnType();
                boolean isCmdMgr=false;
                for(Method rm: ret.getMethods()) if(rm.getParameterCount()==2 && rm.getParameterTypes()[1]==String.class) isCmdMgr=true;
                if(!isCmdMgr) continue;
                m.setAccessible(true);
                try{ Object cand=m.invoke(server); if(cand!=null){ LOGGER.info("[DataHarvester] Found via declared {} -> {}", m.getName(), ret.getName()); commandManager=cand; break; } }catch(Exception ignored){}
            }
        }
        if(commandManager==null){
            try{ commandManager=server.getClass().getMethod("getCommands").invoke(server); LOGGER.info("[DataHarvester] Found via getCommands"); } catch(Exception e){ LOGGER.info("[DataHarvester] getCommands failed {}", e.toString()); throw e; }
        }
        LOGGER.info("[DataHarvester] Using command manager {}", commandManager.getClass().getName());
        for(Method m: commandManager.getClass().getMethods()){
            if(m.getParameterCount()==2 && m.getParameterTypes()[1]==String.class){
                // first param should be ServerCommandSource-like, not String
                if(m.getParameterTypes()[0]==String.class) continue;
                try{
                    LOGGER.info("[DataHarvester] Trying command execute via {} with source {} cmd {}", m.getName(), sourceStack.getClass().getName(), command);
                    Object r=m.invoke(commandManager, sourceStack, command);
                    LOGGER.info("[DataHarvester] Command {} result {}", command, r);
                    if(r instanceof Number) return ((Number)r).intValue();
                    return 0;
                }catch(Exception e){ LOGGER.info("[DataHarvester] Execute via {} failed {}", m.getName(), e.toString()); }
            }
        }
        throw new NoSuchMethodException("performPrefixedCommand not found on " + commandManager.getClass() + " " + Arrays.toString(commandManager.getClass().getMethods()));
    }
}
