package com.oj.judge.service.impl;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.oj.common.auth.utils.TokenUtils;
import com.oj.common.core.enums.HttpCodeEnum;
import com.oj.common.core.utils.ThrowUtils;
import com.oj.common.mybatis.constant.SqlConstant;
import com.oj.common.mybatis.utils.SqlUtils;
import com.oj.judge.mapper.ProblemMapper;
import com.oj.judge.mapper.ProblemSubmitMapper;
import com.oj.judge.model.dto.problem.ProblemAddReq;
import com.oj.judge.model.dto.problem.ProblemQueryReq;
import com.oj.judge.model.dto.problem.ProblemUpdateReq;
import com.oj.judge.model.entity.Problem;
import com.oj.judge.model.entity.ProblemSubmit;
import com.oj.judge.model.enums.ProblemStatusEnum;
import com.oj.judge.model.enums.ProblemSubmitStatusEnum;
import com.oj.judge.model.vo.problem.ProblemVo;
import com.oj.judge.model.vo.problem.SafeProblemVo;
import com.oj.judge.search.sync.ProblemIndexPublisher;
import com.oj.judge.service.ProblemService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

/**
 * @author OJ
 * @description 针对表【problem(题目)】的数据库操作Service实现
 * @createDate 2024-10-15 19:32:36
 */
@Service
public class ProblemServiceImpl extends ServiceImpl<ProblemMapper, Problem>
        implements ProblemService {
    @Resource
    private ProblemSubmitMapper problemSubmitMapper;
    @Resource
    private ProblemIndexPublisher problemIndexPublisher;

    @Override
    @CacheEvict(cacheNames = "judge:problem", key = "'tags'")
    public Long addProblem(ProblemAddReq problemAddRequest) {
        Problem problem = new Problem();
        BeanUtil.copyProperties(problemAddRequest, problem);
        // 设置创建者
        problem.setUserId(TokenUtils.getCurrentUid());
        // 设置其他信息
        problem.setTags(JSONUtil.toJsonStr(problemAddRequest.getTags()));
        problem.setJudgeCase(JSONUtil.toJsonStr(problemAddRequest.getJudgeCase()));
        problem.setJudgeConfig(JSONUtil.toJsonStr(problemAddRequest.getJudgeConfig()));

        boolean result = save(problem);
        ThrowUtils.throwIf(!result, HttpCodeEnum.INTERNAL_SERVER_ERROR, "题目创建失败");
        // 检索是旁路：发事件失败不影响保存结果（契约 0005 §3.1）
        problemIndexPublisher.publish(problem.getId(), "add");
        return problem.getId();
    }

    @Override
    public Page<ProblemVo> listProblemVoByPage(ProblemQueryReq problemQueryRequest) {
        long pageNum = problemQueryRequest.getCurrent();
        long pageSize = problemQueryRequest.getSize();
        Page<Problem> questionPage = page(new Page<>(pageNum, pageSize), getQueryWrapper(problemQueryRequest));
        List<ProblemVo> records = questionPage.getRecords().stream().map(ProblemVo::objToVo)
                .collect(Collectors.toList());
        Page<ProblemVo> page = new Page<>(pageNum, pageSize, questionPage.getTotal());
        page.setRecords(records);
        return page;
    }

    @Override
    @CacheEvict(cacheNames = "judge:problem", key = "'tags'")
    public void updateProblem(ProblemUpdateReq problemUpdateRequest) {
        Problem problem = new Problem();
        BeanUtil.copyProperties(problemUpdateRequest, problem);
        // 设置其他信息
        problem.setTags(JSONUtil.toJsonStr(problemUpdateRequest.getTags()));
        problem.setJudgeCase(JSONUtil.toJsonStr(problemUpdateRequest.getJudgeCase()));
        problem.setJudgeConfig(JSONUtil.toJsonStr(problemUpdateRequest.getJudgeConfig()));

        long id = problemUpdateRequest.getId();
        // 判断是否存在
        Problem oldProblem = getById(id);
        ThrowUtils.throwIf(oldProblem == null, HttpCodeEnum.NOT_EXIST, "题目不存在");
        boolean result = updateById(problem);
        ThrowUtils.throwIf(!result, HttpCodeEnum.INTERNAL_SERVER_ERROR, "题目更新失败");
        problemIndexPublisher.publish(id, "update");
    }

    /**
     * 逻辑删除：行还在（`is_delete=1`），索引里也把 `isDelete` 翻成 true。
     * 查询侧永远带 `isDelete=false` 过滤，因此下架即时生效，不依赖重新索引。
     */
    @Override
    public boolean removeById(java.io.Serializable id) {
        boolean removed = super.removeById(id);
        if (removed) {
            problemIndexPublisher.publish(Long.valueOf(String.valueOf(id)), "delete");
        }
        return removed;
    }

    @Override
    public Page<SafeProblemVo> listSafeProblemVoByPage(ProblemQueryReq problemQueryRequest) {
        long pageNum = problemQueryRequest.getCurrent();
        long pageSize = problemQueryRequest.getSize();
        // 限制爬虫
        ThrowUtils.throwIf(pageSize > 20, HttpCodeEnum.BAD_REQUEST, "pageSize不能超过20");

        Wrapper<Problem> queryWrapper = getQueryWrapper(problemQueryRequest);
        Page<Problem> problemPage = page(new Page<>(pageNum, pageSize), queryWrapper);

        Long uid = TokenUtils.getCurrentUid();
        List<SafeProblemVo> records = problemPage.getRecords().stream()
                .map(problem -> SafeProblemVo.objToVo(problem, uid, problemSubmitMapper))
                .collect(Collectors.toList());
        Page<SafeProblemVo> page = new Page<>(pageNum, pageSize, problemPage.getTotal());
        page.setRecords(records);
        return page;
    }

    public Wrapper<Problem> getQueryWrapper(ProblemQueryReq problemQueryRequest) {
        QueryWrapper<Problem> queryWrapper = new QueryWrapper<>();

        String sortField = problemQueryRequest.getSortField();
        String sortOrder = problemQueryRequest.getSortOrder();
        String keyword = problemQueryRequest.getKeyword();
        ProblemStatusEnum status = ProblemStatusEnum.getEnumByValue(problemQueryRequest.getStatus());
        String difficulty = problemQueryRequest.getDifficulty();
        List<String> tags = problemQueryRequest.getTags();

        // 不查询content和answer，因为很多时候不显示
        queryWrapper.select(Problem.class,
                item -> !item.getColumn().equals("content") && !item.getColumn().equals("answer"));

        if (status != null && !status.equals(ProblemStatusEnum.ALL)) {
            Set<Long> passedIds;
            Set<Long> triedIds;

            switch (status) {
                case ProblemStatusEnum.SOLVED:
                    passedIds = problemSubmitMapper.selectList(new LambdaQueryWrapper<ProblemSubmit>()
                            .select(ProblemSubmit::getProblemId).eq(ProblemSubmit::getUserId, 1L)
                            .eq(ProblemSubmit::getStatus, ProblemSubmitStatusEnum.SUCCEED.getValue()))
                            .stream().map(ProblemSubmit::getProblemId).collect(Collectors.toSet());
                    if (passedIds.isEmpty()) {
                        return null;
                    }
                    queryWrapper.in("id", passedIds);
                    break;
                case ProblemStatusEnum.TRIED:
                    passedIds = problemSubmitMapper.selectList(new LambdaQueryWrapper<ProblemSubmit>()
                            .select(ProblemSubmit::getProblemId).eq(ProblemSubmit::getUserId, 1L)
                            .eq(ProblemSubmit::getStatus, ProblemSubmitStatusEnum.SUCCEED.getValue()))
                            .stream().map(ProblemSubmit::getProblemId).collect(Collectors.toSet());
                    triedIds = problemSubmitMapper.selectList(new LambdaQueryWrapper<ProblemSubmit>()
                            .select(ProblemSubmit::getProblemId).eq(ProblemSubmit::getUserId, 1L)
                            .ne(ProblemSubmit::getStatus, ProblemSubmitStatusEnum.SUCCEED.getValue()))
                            .stream().map(ProblemSubmit::getProblemId).collect(Collectors.toSet());
                    triedIds = (Set<Long>) CollUtil.subtract(triedIds, passedIds);
                    if (triedIds.isEmpty()) {
                        return null;
                    }
                    queryWrapper.in("id", triedIds);
                    break;
                case ProblemStatusEnum.NOLOG:
                    triedIds = problemSubmitMapper.selectList(new LambdaQueryWrapper<ProblemSubmit>()
                            .select(ProblemSubmit::getProblemId).eq(ProblemSubmit::getUserId, 1L))
                            .stream().map(ProblemSubmit::getProblemId).collect(Collectors.toSet());
                    if (!triedIds.isEmpty()) {
                        queryWrapper.notIn("id", triedIds);
                    }
                    break;
                default:
                    break;
            }
        }

        // 拼接查询条件
        boolean likeQuery = StrUtil.isNotBlank(keyword);
        queryWrapper.like(likeQuery, "title", keyword)
                .like(likeQuery, "content", keyword)
                .like(likeQuery, "answer", keyword);
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        queryWrapper.eq(StrUtil.isNotBlank(difficulty), "difficulty", difficulty);

        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(SqlConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    @Override
    @Cacheable(cacheNames = "judge:problem", key = "'tags'")
    public List<String> getProblemTags() {
        return lambdaQuery().select(Problem::getTags).list().stream()
                .flatMap(problem -> JSONUtil.toList(problem.getTags(), String.class).stream())
                .distinct().collect(Collectors.toList());
    }

}
