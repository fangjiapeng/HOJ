package top.hcode.hoj.judge;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileReader;
import cn.hutool.core.io.file.FileWriter;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import top.hcode.hoj.common.exception.CompileError;
import top.hcode.hoj.common.exception.SubmitError;
import top.hcode.hoj.common.exception.SystemError;
import top.hcode.hoj.pojo.entity.Judge;
import top.hcode.hoj.pojo.entity.JudgeCase;
import top.hcode.hoj.pojo.entity.Problem;
import top.hcode.hoj.pojo.entity.ProblemCase;
import top.hcode.hoj.service.impl.JudgeCaseServiceImpl;
import top.hcode.hoj.service.impl.JudgeServiceImpl;
import top.hcode.hoj.service.impl.ProblemCaseServiceImpl;
import top.hcode.hoj.util.Constants;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class JudgeStrategy {

    @Autowired
    private JudgeServiceImpl judgeService;

    @Autowired
    private ProblemTestCaseUtils problemTestCaseUtils;

    @Autowired
    private JudgeCaseServiceImpl judgeCaseService;


    public HashMap<String, Object> judge(Problem problem, Judge judge) {

        HashMap<String, Object> result = new HashMap<>();
        // 编译好的临时代码文件id
        String userFileId = null;
        try {
            // 对用户源代码进行编译 获取tmpfs中的fileId
            userFileId = Compiler.compile(Constants.CompileConfig.getCompilerByLanguage(judge.getLanguage()), judge.getCode(), judge.getLanguage());
            // 测试数据文件所在文件夹
            String testCasesDir = Constants.JudgeDir.TEST_CASE_DIR.getContent() + "/problem_" + problem.getId();
            // 从文件中加载测试数据json
            JSONObject testCasesInfo = problemTestCaseUtils.loadTestCaseInfo(problem.getId(), testCasesDir, problem.getCaseVersion(), !StringUtils.isEmpty(problem.getSpjCode()));
            // 检查是否为spj，同时是否有spj编译完成的文件，若不存在，就先编译生成该spj文件。
            Boolean hasSpjOrNotSpj = checkOrCompileSpj(problem);
            // 如果该题为spj，但是没有spj程序
            if (!hasSpjOrNotSpj) {
                result.put("code", Constants.Judge.STATUS_SYSTEM_ERROR.getStatus());
                result.put("errMsg", "The special judge code does not exist.");
                result.put("time", 0L);
                result.put("memory", 0L);
                return result;
            }

            // 更新状态为评测数据中
            judge.setStatus(Constants.Judge.STATUS_JUDGING.getStatus());
            judgeService.saveOrUpdate(judge);
            // spj程序的名字
            String spjExeName = null;
            if (!StringUtils.isEmpty(problem.getSpjCode())) {
                spjExeName = Constants.RunConfig.getRunnerByLanguage("SPJ-" + problem.getSpjLanguage()).getExeName();
            }
            JudgeRun judgeRun = new JudgeRun(
                    judge.getSubmitId(),
                    problem.getId(),
                    testCasesDir,
                    testCasesInfo,
                    Constants.RunConfig.getRunnerByLanguage(judge.getLanguage()),
                    Constants.RunConfig.getRunnerByLanguage("SPJ-" + problem.getSpjLanguage())
            );
            // 开始测试每个测试点
            List<JSONObject> allCaseResultList = judgeRun.judgeAllCase(
                    userFileId,
                    problem.getTimeLimit() * 1L,
                    problem.getMemoryLimit() * 1024L,
                    false,
                    problem.getIsRemoveEndBlank(),
                    spjExeName);
            // 对全部测试点结果进行评判,获取最终评判结果
            HashMap<String, Object> judgeInfo = getJudgeInfo(allCaseResultList, problem, judge);
            return judgeInfo;
        } catch (SystemError systemError) {
            result.put("code", Constants.Judge.STATUS_SYSTEM_ERROR.getStatus());
            result.put("errMsg", "Oops, something has gone wrong with the judgeServer. Please report this to administrator.");
            result.put("time", 0L);
            result.put("memory", 0L);
            log.error("题号为：" + problem.getId() + "的题目，提交id为" + judge.getSubmitId() + "在评测过程中发生系统性的异常------------------->{}",
                    systemError.getMessage() + "\n" + systemError.getStderr());
        } catch (SubmitError submitError) {
            result.put("code", Constants.Judge.STATUS_SUBMITTED_FAILED.getStatus());
            result.put("errMsg", submitError.getMessage() + ":" + submitError.getStderr());
            result.put("time", 0L);
            result.put("memory", 0L);
        } catch (CompileError compileError) {
            result.put("code", Constants.Judge.STATUS_COMPILE_ERROR.getStatus());
            result.put("errMsg", compileError.getStderr());
            result.put("time", 0L);
            result.put("memory", 0L);
        } catch (Exception e) {
            result.put("code", Constants.Judge.STATUS_SYSTEM_ERROR.getStatus());
            result.put("errMsg", "Oops, something has gone wrong with the judgeServer. Please report this to administrator.");
            result.put("time", 0L);
            result.put("memory", 0L);
            log.error("题号为：" + problem.getId() + "的题目，提交id为" + judge.getSubmitId() + "在评测过程中发生系统性的异常-------------------->{}", e.getMessage());
        } finally {

            // 删除tmpfs内存中的用户代码可执行文件
            if (!StringUtils.isEmpty(userFileId)) {
                SandboxRun.delFile(userFileId);
            }
        }
        return result;
    }


    public Boolean checkOrCompileSpj(Problem problem) throws CompileError, SystemError {
        // 如果是需要特判的题目，则需要检测特批程序是否已经编译，否则进行编译
        if (!StringUtils.isEmpty(problem.getSpjCode())) {
            Constants.CompileConfig spjCompiler = Constants.CompileConfig.getCompilerByLanguage(problem.getSpjLanguage());
            // 如果不存在该已经编译好的特批程序，则需要再次进行编译
            if (!FileUtil.exist(Constants.JudgeDir.SPJ_WORKPLACE_DIR.getContent() + "/" + problem.getId() + "/" + spjCompiler.getExeName())) {
                return Compiler.compileSpj(problem.getSpjCode(), problem.getId(), problem.getSpjLanguage());
            }
        }
        return true;
    }

    // 获取判题的运行时间，运行空间，OI得分
    public HashMap<String, Object> computeResultInfo(List<JSONObject> testCaseResultList, Boolean isACM, Integer errorCaseNum, Integer totalScore) {
        HashMap<String, Object> result = new HashMap<>();
        // 用时和内存占用保存为多个测试点中最长的
        testCaseResultList.stream().max(Comparator.comparing(t -> t.getLong("time")))
                .ifPresent(t -> result.put("time", t.getLong("time")));

        testCaseResultList.stream().max(Comparator.comparing(t -> t.getLong("memory")))
                .ifPresent(t -> result.put("memory", t.getLong("memory")));

        // OI题目计算得分
        if (!isACM) {
            int score = 0;
            // 全对的直接用总分
            if (errorCaseNum == 0) {
                result.put("score", totalScore);
            } else {
                for (JSONObject testcaseResult : testCaseResultList) {
                    score += testcaseResult.getInt("score", 0);
                }
                result.put("socre", score);
            }
        }
        return result;
    }

    // 进行最终测试结果的判断（除编译失败外的评测状态码和时间，空间,OI题目的得分）
    public HashMap<String, Object> getJudgeInfo(List<JSONObject> testCaseResultList, Problem problem, Judge judge) {

        boolean isACM = problem.getType() == Constants.Contest.TYPE_ACM.getCode();

        List<JSONObject> errorTestCaseList = new LinkedList<>();

        List<JudgeCase> allCaseResList = new LinkedList<>();

        // 记录所有测试点的结果
        testCaseResultList.stream().forEach(jsonObject -> {
            Integer time = jsonObject.getLong("time").intValue();
            Integer memory = jsonObject.getLong("memory").intValue();
            Integer status = jsonObject.getInt("status");
            Long caseId = jsonObject.getLong("caseId");
            JudgeCase judgeCase = new JudgeCase();
            judgeCase.setTime(time).setMemory(memory)
                    .setStatus(status)
                    .setPid(problem.getId())
                    .setUid(judge.getUid())
                    .setCaseId(caseId)
                    .setSubmitId(judge.getSubmitId());
            // 过滤出结果不是AC的测试点结果 同时如果是IO题目 则得分为0
            if (jsonObject.getInt("status").intValue() != Constants.Judge.STATUS_ACCEPTED.getStatus()) {
                errorTestCaseList.add(jsonObject);
                if (!isACM) {
                    judgeCase.setScore(0);
                }
            } else { // 如果是AC同时为IO题目测试点，则获得该点的得分
                if (!isACM) {
                    judgeCase.setScore(jsonObject.getInt("score").intValue());
                }
            }
            allCaseResList.add(judgeCase);
        });

        // 更新到数据库
        boolean addCaseRes = judgeCaseService.saveBatch(allCaseResList);
        if (!addCaseRes) {
            log.error("题号为：" + problem.getId() + "，提交id为：" + judge.getSubmitId() + "的各个测试数据点的结果更新到数据库操作失败");
        }

        // 获取判题的运行时间，运行空间，OI得分
        HashMap<String, Object> result = computeResultInfo(testCaseResultList, isACM, errorTestCaseList.size(), problem.getIoScore());

        // 如果该题为ACM类型的题目，多个测试点全部正确则AC，否则取第一个错误的测试点的状态
        // 如果该题为OI类型的题目, 若多个测试点全部正确则AC，若全部错误则取第一个错误测试点状态，否则为部分正确
        if (errorTestCaseList.size() == 0) { // 全部测试点正确，则为AC
            result.put("code", Constants.Judge.STATUS_ACCEPTED.getStatus());
        } else if (isACM || errorTestCaseList.size() == testCaseResultList.size()) {
            result.put("code", errorTestCaseList.get(0).getInt("status"));
            result.put("errMsg", errorTestCaseList.get(0).getStr("errMsg", ""));
        } else {
            result.put("code", Constants.Judge.STATUS_PARTIAL_ACCEPTED.getStatus());
        }
        return result;
    }

}