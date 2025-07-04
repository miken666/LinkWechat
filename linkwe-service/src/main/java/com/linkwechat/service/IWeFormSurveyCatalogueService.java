package com.linkwechat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.linkwechat.common.config.WeSideBarConfig;
import com.linkwechat.domain.WeFormSurveyCatalogue;
import com.linkwechat.domain.form.query.WeAddFormSurveyCatalogueQuery;
import com.linkwechat.domain.form.query.WeFormSurveyCatalogueQuery;
import com.linkwechat.domain.material.vo.WeMaterialNewVo;

import java.util.List;

/**
 * 问卷-目录列表(WeFormSurveyCatalogue)
 *
 * @author danmo
 * @since 2022-09-20 18:02:56
 */
public interface IWeFormSurveyCatalogueService extends IService<WeFormSurveyCatalogue> {

    Long add(WeAddFormSurveyCatalogueQuery query);

    void deleteSurvey(List<Long> ids);

    void updateSurvey(WeAddFormSurveyCatalogueQuery query);

    WeFormSurveyCatalogue getInfo(Long id);

    List<WeFormSurveyCatalogue> getList(WeFormSurveyCatalogueQuery query);


    /**
     * 获取的表单转化为素材
     * @param query
     * @return
     */
    List<WeMaterialNewVo> findFormToWeMaterialNewVo(WeFormSurveyCatalogueQuery query);

    void updateStatus(WeFormSurveyCatalogueQuery query);

    void deleteFormSurveyGroup(Long groupId);

    WeFormSurveyCatalogue getWeFormSurveyCatalogueById(Long id);

    /**
     * 获取所有的问卷，跳过租户拦截判断
     *
     * @return
     */
    List<WeFormSurveyCatalogue> getListIgnoreTenantId();



    /**
     * 更新，跳过租户判断
     *
     * @param weFormSurveyCatalogue
     */
    void updateByIdIgnoreTenantId(WeFormSurveyCatalogue weFormSurveyCatalogue);



}
