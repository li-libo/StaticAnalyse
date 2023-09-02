package com.yonyou.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.Filter;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.reflect.code.CtInvocationImpl;

import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * @author lilibo
 * @create 2023-07-11 10:42
 */
public class StaticAnalyseUtils {

    public static void main(String[] args) throws SQLException, ClassNotFoundException {
        Instant start = Instant.now();
        String projectPath = args[0];
        String projectCode = args[1];
        String jdbcUrl = args[2];
        String username = args[3];
        String password = args[4];
        String branch = args[5];
        Launcher launcher = new Launcher();
        launcher.addInputResource(projectPath);
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);
        launcher.buildModel();
        CtModel model = launcher.getModel();
        Map<String, Set<String>> methodCallMap = new HashMap<>();
        Map<String, Set<String>> interfaceAndImplMap = new HashMap<>();
        Set<CtMethod<?>> allMethodSet = new HashSet<>();
        Set<CtInterface> interfaceSet = new HashSet<>();
        Set<CtClass> absClassSet = new HashSet<>();
        Set<CtMethod<?>> interfaceMethodSet = new HashSet<>();
        Set<CtMethod<?>> absMethodSet = new HashSet<>();
        Set<CtMethod<?>> implMethodSet = new HashSet<>();
        //收集接口和实现类
        Instant start1 = Instant.now();
        for (CtType<?> ctType : model.getAllTypes()) {
            try{
                if(ctType instanceof CtInterface) {
                    interfaceSet.add((CtInterface) ctType);
                }else if(ctType instanceof CtClass && ((CtClass)ctType).isAbstract()) {
                    absClassSet.add((CtClass) ctType);
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        }
        //收集所有方法
        for (CtType<?> ctClass : model.getAllTypes()) {
            try {
                Set<CtMethod<?>> ctlMethodSet = ctClass.getAllMethods();
                allMethodSet.addAll(ctlMethodSet);
                for(CtInterface inter : interfaceSet) {
                    if(ctClass.isSubtypeOf(inter.getReference())) {
                        //粗略收集所有接口和实现类方法
                        interfaceMethodSet.addAll(inter.getMethods());
                        implMethodSet.addAll(ctClass.getMethods());
                    }
                }
                for(CtClass absClass : absClassSet) {
                    if(ctClass.isSubtypeOf(absClass.getReference())) {
                        //粗略收集所有抽象类和实现类方法
                        absMethodSet.addAll(absClass.getMethods());
                        implMethodSet.addAll(ctClass.getMethods());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println("所有方法数量 = " + allMethodSet.size() + ", 接口方法数量 = " + interfaceMethodSet.size() + ", 抽象方法数量 = " + absMethodSet.size() + ", 实现类方法数量 = " + implMethodSet.size());
        Instant end1 = Instant.now();
        System.out.println("第1阶段所有方法收集, projectPath = " + projectPath + "分析耗时" + Duration.between(start, end1).toMillis() / 1000 + "s");
        // 遍历收集接口实现类重写的方法
        start1 = Instant.now();
        for (CtMethod<?> interfaceMethod : interfaceMethodSet) {
            try{
                // 检查类中是否包含重写的方法
                for (CtMethod<?> classMethod : implMethodSet) {
                    if (classMethod.isOverriding(interfaceMethod)) {
                        //System.out.println("类方法" + classMethod.getDeclaringType().getQualifiedName() + "#" + classMethod.getSignature() + " 重写了接口方法: " + interfaceMethod.getDeclaringType().getQualifiedName() + "#" + interfaceMethod.getSignature());
                        String interfaceName = interfaceMethod.getDeclaringType().getQualifiedName() + "#" + interfaceMethod.getSignature();
                        String implName = classMethod.getDeclaringType().getQualifiedName() + "#" + classMethod.getSignature();
                        interfaceAndImplMap.putIfAbsent(interfaceName, new HashSet<>());
                        //记录接口实现类关系
                        if (!interfaceName.equals(implName)) {
                            interfaceAndImplMap.get(interfaceName).add(implName);
                        }
                    }
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        }
        end1 = Instant.now();
        System.out.println("寻找到被重写的接口数量为" + interfaceAndImplMap.size());
        System.out.println("第2阶段分析接口实现类, projectPath = " + projectPath + "分析耗时" + Duration.between(start1, end1).toMillis() / 1000 + "s");
        start1 = Instant.now();
        // 遍历收集抽象类重写的方法
        for (CtMethod<?> absMethod : absMethodSet) {
            try{
                // 检查类中是否包含重写的方法
                for (CtMethod<?> classMethod : implMethodSet) {
                    if (classMethod.isOverriding(absMethod)) {
                        //System.out.println("类方法" + classMethod.getDeclaringType().getQualifiedName() + "#" + classMethod.getSignature() + " 重写了抽象方法: " + absMethod.getDeclaringType().getQualifiedName() + "#" + absMethod.getSignature());
                        String absName = absMethod.getDeclaringType().getQualifiedName() + "#" + absMethod.getSignature();
                        String implName = classMethod.getDeclaringType().getQualifiedName() + "#" + classMethod.getSignature();
                        interfaceAndImplMap.putIfAbsent(absName, new HashSet<>());
                        //记录抽象类和实现类关系
                        if (!absName.equals(implName)) {
                            interfaceAndImplMap.get(absName).add(implName);
                        }
                    }
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        }
        end1 = Instant.now();
        System.out.println("寻找到被重写的接口+抽象类数量为" + interfaceAndImplMap.size());
        System.out.println("第3阶段分析抽象方法, projectPath = " + projectPath + "分析耗时" + Duration.between(start1, end1).toMillis() / 1000 + "s");
        start1 = Instant.now();
        for (CtMethod<?> method : allMethodSet) {
            CtType<?> declaringType = method.getDeclaringType();
            List<CtInvocation<?>> methodInvocations = method.getElements(new TypeFilter<>(CtInvocation.class));
            if (!methodInvocations.isEmpty()) {
                Set<String> calleeSet = new HashSet<>();
                for (CtInvocation<?> methodInvocation : methodInvocations) {
                    try {
                        CtExecutableReference<?> executable = ((CtInvocationImpl<?>) methodInvocation).getExecutable();
                        CtTypeReference<?> type = executable.getDeclaringType();
                        String methodSignature = executable.getDeclaration() == null ? executable.getSignature() : executable.getDeclaration().getSignature();
                        String callee = type.getPackage() + "." + type.getSimpleName() + "#" + methodSignature;
                        calleeSet.add(callee);
                        //如果被调用者是接口,将实现类方法也一并塞入
                        if (interfaceAndImplMap.containsKey(callee)) {
                            Set<String> implCalleeSet = interfaceAndImplMap.get(callee);
                            calleeSet.addAll(implCalleeSet);
                        }
                    } catch (Exception e) {
                        //e.printStackTrace();
                    }
                }
                methodCallMap.put(declaringType.getPackage() + "." + declaringType.getSimpleName() + "#" + method.getSignature(), calleeSet);
            }
        }
        end1 = Instant.now();
        System.out.println("第4阶段分析调用关系, projectPath = " + projectPath + "分析耗时" + Duration.between(start1, end1).toMillis() / 1000 + "s");
        System.out.println("解析完毕");
        System.out.println("分析总耗时projectPath = " + projectPath + "分析耗时" + Duration.between(start, end1).toMillis() / 1000 + "s");

        //开始插入数据
        start1 = Instant.now();
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(jdbcUrl, username, password);
            //设置为手动提交
            conn.setAutoCommit(false);
            String deleteSql = "delete from ydt_code_static_method_link where project_code = ? and branch = ?";
            PreparedStatement ps = conn.prepareStatement(deleteSql);
            ps.setString(1, projectCode);
            ps.setString(2, branch);
            ps.executeUpdate();
            // 批量插入数据
            int batchSize = 5000; // 设置每批次的数据量
            int count = 0;
            String batchInsertSql = "insert into ydt_code_static_method_link (caller, callee, callerLine, callerType, branch, project_code, md5) VALUES (?, ?, ?, ?, ?, ?, ?)";
            ps = conn.prepareStatement(batchInsertSql);
            Set<Map.Entry<String, Set<String>>> entries = methodCallMap.entrySet();
            for (Map.Entry<String, Set<String>> entry : entries) {
                String caller = entry.getKey();
                Set<String> calleeList = entry.getValue();
                for (String callee : calleeList) {
                    ps.setString(1, caller);
                    ps.setString(2, callee);
                    ps.setString(3, null);
                    ps.setString(4, null);
                    ps.setString(5, branch);
                    ps.setString(6, projectCode);
                    String key = caller + "-" + callee + "-" + branch + "-" + projectCode;
                    ps.setString(7, md5Encode(key));
                    ps.addBatch();
                    if (++count % batchSize == 0) {
                        // 执行批量插入并提交
                        ps.executeBatch();
                        conn.commit();
                    }
                }
            }
            // 执行最后一批次并提交
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            if (conn != null) {
                conn.rollback();
            }
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
        end1 = Instant.now();
        System.out.println("projectPath = " + projectCode + "插入耗时" + Duration.between(start1, end1).toMillis() / 1000 + "s");
        System.out.println("projectPath = " + projectCode + "分析+插入耗时" + Duration.between(start, end1).toMillis() / 1000 + "s");
    }

    public static String md5Encode(String s) {
        String MD5String = "";
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MD5String = Base64.getEncoder().encodeToString(md5.digest(s.getBytes("utf-8")));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return MD5String;
    }

}