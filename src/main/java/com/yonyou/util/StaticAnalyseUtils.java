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
//        Set<String> keyMethodNameSet = new HashSet<>();
//        Set<String> allMethodNameSet = new HashSet<>();
        for (CtType<?> ctClass : model.getAllTypes()) {
            try{
                Set<CtMethod<?>> ctlMethodSet = ctClass.getAllMethods();
                allMethodSet.addAll(ctlMethodSet);
//                for (CtMethod<?> method : ctlMethodSet) {
//                    CtType<?> declaringType = method.getDeclaringType();
//                    String key = declaringType.getPackage() + "." + declaringType.getSimpleName() + "#" + method.getSignature();
//                    //解析方法注解
//                    List<CtAnnotation<? extends Annotation>> annotations = method.getAnnotations();
//                    if (annotations != null && annotations.size() > 0) {
//                        for (CtAnnotation<?> ctAnnotation : annotations) {
//                            if (ctAnnotation != null && ctAnnotation.toString().contains(annotationClassName)) {
//                                keyMethodNameSet.add(key);
//                            }
//                        }
//                    }
//                    allMethodNameSet.add(key);
//                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 遍历所有接口找到实现类
        for (CtInterface<?> ctInterface : model.getElements(new TypeFilter<>(CtInterface.class))) {
            try {
                //System.out.println("接口: " + ctInterface.getSimpleName());
                // 遍历接口的方法
                for (CtMethod<?> interfaceMethod : ctInterface.getMethods()) {
                    //System.out.println("接口方法: " + interfaceMethod.getSignature());
                    // 遍历所有类
                    for (CtClass<?> ctClass : model.getElements(new TypeFilter<>(CtClass.class))) {
                        // 检查是否是接口实现类
                        if (ctClass.getReferencedTypes().contains(ctInterface.getReference())) {
                            // 检查类中是否包含重写的方法
                            for (CtMethod<?> classMethod : ctClass.getMethods()) {
                                if (classMethod.isOverriding(interfaceMethod)) {
                                    //System.out.println("类方法" + ctClass.getQualifiedName() + "#" + classMethod.getSignature() + " 重写了接口方法: " + ctInterface.getQualifiedName() + "#" + interfaceMethod.getSignature());
                                    String interfaceName = ctInterface.getQualifiedName() + "#" + interfaceMethod.getSignature();
                                    String implName = ctClass.getQualifiedName() + "#" + classMethod.getSignature();
                                    interfaceAndImplMap.putIfAbsent(interfaceName, new HashSet<>());
                                    //记录接口实现类关系
                                    interfaceAndImplMap.get(interfaceName).add(implName);
                                }
                            }
                        }
                    }
                }
            }catch (Exception e) {
                e.printStackTrace();
            }
        }
        for (CtMethod<?> method : allMethodSet) {
            CtType<?> declaringType = method.getDeclaringType();
            List<CtInvocation<?>> methodInvocations = method.getElements(new TypeFilter<>(CtInvocation.class));
            if (!methodInvocations.isEmpty()) {
                Set<String> calleeSet = new HashSet<>();
                for (CtInvocation<?> methodInvocation : methodInvocations) {
                    try{
                        CtExecutableReference<?> executable = ((CtInvocationImpl<?>) methodInvocation).getExecutable();
                        CtTypeReference<?> type = executable.getDeclaringType();
                        String methodSignature = executable.getDeclaration().getSignature();
                        String callee = type.getPackage() + "." + type.getSimpleName() + "#" + methodSignature;
                        calleeSet.add(callee);
                        //如果被调用者是接口,将实现类方法也一并塞入
                        if(interfaceAndImplMap.containsKey(callee)) {
                            Set<String> implCalleeSet = interfaceAndImplMap.get(callee);
                            calleeSet.addAll(implCalleeSet);
                        }
                    }catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                methodCallMap.put(declaringType.getPackage() + "." + declaringType.getSimpleName() + "#" + method.getSignature(), calleeSet);
            }
        }
        System.out.println("解析完毕");
        Instant end = Instant.now();
        System.out.println("projectPath = " + projectCode + "分析耗时" + Duration.between(start, end).toMillis()/1000 + "s");
        start = Instant.now();
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection conn = null;
        try{
            conn = DriverManager.getConnection(jdbcUrl, username, password);
            conn.setAutoCommit(false);
            String deleteSql = "delete from ydt_code_static_method_link where project_code = ? and branch = ?";
            PreparedStatement ps = conn.prepareStatement(deleteSql);
            ps.setString(1, projectCode);
            ps.setString(2, branch);
            ps.executeUpdate();
            String batchInsertSql = "insert into ydt_code_static_method_link (caller, callee, callerLine, callerType, branch, project_code, md5) VALUES (?, ?, ?, ?, ?, ?, ?)";
            ps = conn.prepareStatement(batchInsertSql);
            Set<Map.Entry<String, Set<String>>> entries = methodCallMap.entrySet();
            for(Map.Entry<String, Set<String>> entry : entries) {
                String caller = entry.getKey();
                Set<String> calleeList = entry.getValue();
                for(String callee : calleeList) {
                    ps.setString(1, caller);
                    ps.setString(2, callee);
                    ps.setString(3, null);
                    ps.setString(4, null);
                    ps.setString(5, branch);
                    ps.setString(6, projectCode);
                    String key = caller + "-" + callee + "-" + branch + "-" + projectCode;
                    ps.setString(7, md5Encode(key));
                    ps.addBatch();
                }
            }
            int[] result = ps.executeBatch();
            conn.commit();
        }catch (Exception e) {
            if(conn != null) {
                conn.rollback();
            }
        }finally {
            if(conn != null) {
                conn.close();
            }
        }
        end = Instant.now();
        System.out.println("projectPath = " + projectCode + "插入耗时" + Duration.between(start, end).toMillis()/1000 + "s");
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