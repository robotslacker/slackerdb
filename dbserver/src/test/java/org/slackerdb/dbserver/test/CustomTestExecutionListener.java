package org.slackerdb.dbserver.test;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

public class CustomTestExecutionListener implements TestExecutionListener {

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        // 全局前置步骤
        System.out.println("Running global init...");
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        // 关闭slackerDB的服务
        System.out.println("Running global cleanup...");
    }
}

