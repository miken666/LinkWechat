package com.linkwechat.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwechat.common.annotation.SynchRecord;
import com.linkwechat.common.constant.SynchRecordConstants;
import com.linkwechat.common.context.SecurityContextHolder;
import com.linkwechat.common.core.domain.AjaxResult;
import com.linkwechat.common.core.domain.model.LoginUser;
import com.linkwechat.common.enums.TagSynchEnum;
import com.linkwechat.common.utils.SecurityUtils;
import com.linkwechat.common.utils.SnowFlakeUtil;
import com.linkwechat.common.utils.StringUtils;
import com.linkwechat.config.rabbitmq.RabbitMQSettingConfig;
import com.linkwechat.domain.WeTag;
import com.linkwechat.domain.WeTagGroup;
import com.linkwechat.domain.wecom.entity.customer.tag.WeCorpTagEntity;
import com.linkwechat.domain.wecom.entity.customer.tag.WeCorpTagGroupEntity;
import com.linkwechat.domain.wecom.query.customer.tag.WeCorpTagListQuery;
import com.linkwechat.domain.wecom.query.customer.tag.WeUpdateCorpTagQuery;
import com.linkwechat.domain.wecom.vo.WeResultVo;
import com.linkwechat.domain.wecom.vo.customer.tag.WeCorpTagListVo;
import com.linkwechat.fegin.QwCustomerClient;
import com.linkwechat.mapper.WeTagGroupMapper;
import com.linkwechat.service.IWeTagGroupService;
import com.linkwechat.service.IWeTagService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
public class WeTagGroupServiceImpl extends ServiceImpl<WeTagGroupMapper, WeTagGroup> implements IWeTagGroupService {


    @Autowired
    private IWeTagService iWeTagService;


    @Autowired
    private QwCustomerClient qwCustomerClient;

    @Autowired
    private RabbitMQSettingConfig rabbitMQSettingConfig;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public List<WeTagGroup> selectWeTagGroupList(WeTagGroup weTagGroup) {
        List<WeTagGroup> weTagGroups = this.baseMapper.selectWeTagGroupList(weTagGroup);
        if (CollectionUtil.isNotEmpty(weTagGroups)) {
            List<String> groupId = weTagGroups.stream().map(WeTagGroup::getGroupId).distinct().collect(Collectors.toList());
            List<WeTag> tagList = iWeTagService.getTagListByGroupIds(groupId);
            if (CollectionUtil.isNotEmpty(tagList)) {
                Map<String, List<WeTag>> tagMap = tagList.stream().collect(Collectors.groupingBy(WeTag::getGroupId));
                for (WeTagGroup tagGroup : weTagGroups) {
                    if (tagMap.containsKey(tagGroup.getGroupId())) {
                        tagGroup.setWeTags(tagMap.get(tagGroup.getGroupId()));
                    }
                }
            }
        }
        return weTagGroups;
    }

    @Override
    @Transactional
    public void insertWeTagGroup(WeTagGroup weTagGroup) {

        try {
            List<WeTag> weTags = weTagGroup.getWeTags();
            if (CollectionUtil.isNotEmpty(weTags)) {
                //客户企业标签(需要同步到企业微信端)
                if (weTagGroup.getGroupTagType().equals(new Integer(1))) {
                    iWeTagService.addWxTag(weTagGroup, weTags);
                } else {
                    weTagGroup.setId(SnowFlakeUtil.nextId());
                    weTagGroup.setGroupId(String.valueOf(weTagGroup.getId()));
                    weTags.stream().forEach(k -> {
                        Long weTagId = SnowFlakeUtil.nextId();
                        k.setId(weTagId);
                        k.setTagId(String.valueOf(weTagId));
                    });
                }

                //群标签或者个人标签(无需同步企业微信端)
                if (this.save(weTagGroup)) {
                    weTags.stream().forEach(k -> {
                        k.setGroupId(
                                StringUtils.isNotEmpty(weTagGroup.getGroupId())
                                        ? weTagGroup.getGroupId()
                                        : String.valueOf(weTagGroup.getId())
                        );
                    });
                    iWeTagService.saveBatch(weTags);
                }
            }
        } catch (Exception e) {
            throw e;
        }


    }


    @Override
    @Transactional
    public void updateWeTagGroup(WeTagGroup weTagGroup) {

        //更新标签组名称
        if (this.updateById(weTagGroup)) {
            WeTagGroup oldWeTagGroup = this.getById(weTagGroup.getId());
            if (oldWeTagGroup != null) {

                if (!oldWeTagGroup.getGroupName().equals(weTagGroup.getGroupName())) {//标签名不同则更新企业微信端
                    qwCustomerClient.editCorpTag(WeUpdateCorpTagQuery.builder()
                            .id(weTagGroup.getGroupId())
                            .name(weTagGroup.getGroupName())
                            .build());
                }

                List<WeTag> weTags = weTagGroup.getWeTags();

                List<WeTag> removeWeTags = new ArrayList<>();

                if (CollectionUtil.isNotEmpty(weTags)) {
                    //新增的标签
                    List<WeTag> addWeTags = weTags.stream().filter(v -> StringUtils.isEmpty(v.getTagId())).collect(Collectors.toList());
                    if (CollectionUtil.isNotEmpty(addWeTags)) {
                        if (weTagGroup.getGroupTagType().equals(new Integer(1))) {
                            iWeTagService.addWxTag(weTagGroup, addWeTags);
                        } else {
                            weTags.stream().forEach(k -> {
                                if (StringUtils.isEmpty(k.getTagId())) {
                                    Long weTagId = SnowFlakeUtil.nextId();
                                    k.setId(weTagId);
                                    k.setTagId(String.valueOf(weTagId));
                                }

                            });
                        }
                        addWeTags.stream().forEach(k -> k.setGroupId(weTagGroup.getGroupId()));
                        iWeTagService.saveBatch(addWeTags);
                    }

                    removeWeTags.addAll(
                            iWeTagService.list(new LambdaQueryWrapper<WeTag>().notIn(WeTag::getTagId,
                                    weTags.stream().map(WeTag::getTagId).collect(Collectors.toList()))
                                    .eq(WeTag::getGroupId, weTagGroup.getGroupId()))
                    );

                } else {//删除所有标签
                    removeWeTags.addAll(
                            iWeTagService.list(new LambdaQueryWrapper<WeTag>()
                                    .eq(WeTag::getGroupId, weTagGroup.getGroupId()))
                    );
                }

                if (CollectionUtil.isNotEmpty(removeWeTags)) {
                    iWeTagService.removeWxTag(weTagGroup.getGroupId(), removeWeTags, false);
                }


            }


        }

    }

    @Override
    public void deleteWeTagGroupByIds(String[] ids) {

        List<WeTagGroup> weTagGroups = this.list(new LambdaQueryWrapper<WeTagGroup>()
                .in(WeTagGroup::getGroupId, ListUtil.toList(ids)));

        if (CollectionUtil.isNotEmpty(weTagGroups)) {
            weTagGroups.forEach(k -> {
                if (this.removeById(k.getId())) {
                    iWeTagService.removeWxTag(k.getGroupId(),
                            iWeTagService.list(new LambdaQueryWrapper<WeTag>()
                                    .eq(WeTag::getGroupId, k.getId())),
                            true,
                            k.getGroupTagType() == 1
                    );
                }
            });

        }


    }

    @Override
    @SynchRecord(synchType = SynchRecordConstants.SYNCH_CUSTOMER_TAG)
    public void synchWeTags() {

        LoginUser loginUser = SecurityUtils.getLoginUser();
        rabbitTemplate.convertAndSend(rabbitMQSettingConfig.getWeSyncEx(), rabbitMQSettingConfig.getWeGroupTagRk(), JSONObject.toJSONString(loginUser));


    }

    @Override
    public void synchWeGroupTagHandler(String msg) {

        LoginUser loginUser = JSONObject.parseObject(msg, LoginUser.class);
        SecurityContextHolder.setCorpId(loginUser.getCorpId());
        SecurityContextHolder.setUserName(loginUser.getUserName());
        SecurityContextHolder.setUserId(String.valueOf(loginUser.getSysUser().getUserId()));
        SecurityContextHolder.setUserType(loginUser.getUserType());

        this.synchWeGroupAndTag(null, null);

    }


    @Override
    @Transactional
    @Async
    public void synchWeGroupAndTag(String businessId, String tagType) {


        WeCorpTagListQuery weCorpTagListQuery = new WeCorpTagListQuery();


        if (StringUtils.isNotEmpty(tagType)) {
            if (TagSynchEnum.TAG_TYPE.getType().equals(tagType)) {
                weCorpTagListQuery.setTag_id(
                        ListUtil.toList(businessId)
                );
            } else if (TagSynchEnum.GROUP_TAG_TYPE.getType().equals(tagType)) {
                weCorpTagListQuery.setGroup_id(
                        ListUtil.toList(businessId)
                );
            }
        }


        List<WeTagGroup> weTagGroups = new ArrayList<>();


        WeCorpTagListVo weCorpTagListVo = qwCustomerClient.getCorpTagList(weCorpTagListQuery).getData();

        if (null != weCorpTagListVo) {
            List<WeCorpTagGroupEntity> tagGroup = weCorpTagListVo.getTagGroup();
            if (CollectionUtil.isNotEmpty(tagGroup)) {

                tagGroup.stream().forEach(k -> {
                    WeTagGroup weTagGroup = new WeTagGroup();
                    weTagGroup.setId(SnowFlakeUtil.nextId());
                    weTagGroup.setGroupName(k.getGroup_name());
                    weTagGroup.setGroupId(k.getGroup_id());
                    weTagGroup.setGroupTagType(1);
                    weTagGroup.setCreateBy(SecurityUtils.getUserName());
                    weTagGroup.setCreateById(SecurityUtils.getUserId());
                    weTagGroup.setCreateTime(new Date());
                    weTagGroup.setUpdateBy(SecurityUtils.getUserName());
                    weTagGroup.setUpdateTime(new Date());
                    weTagGroup.setUpdateById(SecurityUtils.getUserId());
                    List<WeCorpTagEntity> tag = k.getTag();
                    if (CollectionUtil.isNotEmpty(tag)) {
                        List<WeTag> weTags = new ArrayList<>();
                        tag.stream().forEach(v -> {
                            WeTag weTag = new WeTag();
                            weTag.setId(SnowFlakeUtil.nextId());
                            weTag.setTagId(v.getId());
                            weTag.setGroupId(weTagGroup.getGroupId());
                            weTag.setName(v.getName());
                            weTag.setCreateTime(new Date());
                            weTag.setCreateBy(SecurityUtils.getUserName());
                            weTag.setCreateById(SecurityUtils.getUserId());
                            weTag.setUpdateTime(new Date());
                            weTag.setUpdateBy(SecurityUtils.getUserName());
                            weTag.setUpdateById(SecurityUtils.getUserId());
                            weTags.add(weTag);
                        });
                        weTagGroup.setWeTags(weTags);
                    }
                    weTagGroups.add(weTagGroup);
                });
            }
        }

        if (StringUtils.isEmpty(tagType) && StringUtils.isEmpty(businessId)) {
            //先移除所有
            List<WeTagGroup> oldWeTagGroups = this.list(new LambdaQueryWrapper<WeTagGroup>()
                    .eq(WeTagGroup::getGroupTagType, 1));
            if (CollectionUtil.isNotEmpty(oldWeTagGroups)) {

                if (this.removeByIds(oldWeTagGroups.stream()
                        .map(WeTagGroup::getId).collect(Collectors.toList()))) {

                    iWeTagService.remove(new LambdaQueryWrapper<WeTag>()
                            .in(WeTag::getGroupId,
                                    oldWeTagGroups.stream().map(WeTagGroup::getGroupId).collect(Collectors.toList())
                            ));
                }
            }
        }

        //根据企业微信返回的，存在状态恢复，不存在的新增
        if (CollectionUtil.isNotEmpty(weTagGroups)) {
            this.baseMapper.batchAddOrUpdate(weTagGroups);
        }

        List<List<WeTag>> handleWeTags
                = weTagGroups.stream().map(WeTagGroup::getWeTags).collect(Collectors.toList());

        if (CollectionUtil.isNotEmpty(handleWeTags)) {
            iWeTagService.batchAddOrUpdate(handleWeTags.stream().collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll));
        }

    }

    @Override
    public List<WeTagGroup> getTagGroupPageList(WeTagGroup tagGroup) {
        List<Long> tagGroupIds = this.baseMapper.getTagGroupIds(tagGroup);
        return null;
    }

    @Override
    public List<WeTagGroup> getTagGroupList(WeTagGroup tagGroup) {
        return this.baseMapper.getTagGroupList(tagGroup);
    }


}
