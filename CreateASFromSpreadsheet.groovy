package customfunctions.html5Ui

import com.sas.oprisk.server.behavior.SecurityUtils;
import com.sas.oprisk.framework.server.persistence.PersistenceSession
import com.sas.oprisk.framework.server.persistence.ThreadLocalPersistenceSession
import com.sas.oprisk.server.LinkType
import com.sas.oprisk.server.User
import com.sas.oprisk.server.web.cpb.runtime.MonitorUIContextUtil
import com.sas.solutions.cpb.docs.FunctionDescription
import com.sas.solutions.cpb.docs.FunctionExample
import com.sas.solutions.cpb.docs.FunctionExamples
import com.sas.solutions.cpb.docs.FunctionReturnDescription
import com.sas.solutions.cpb.docs.FunctionReturnType
import com.sas.ui.cpb3.EvaluationException
import com.sas.ui.cpb3.context.Context
import com.sas.ui.cpb3.expr.AFunction
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import com.sas.oprisk.server.BusinessObjectTypeInfo
import com.sas.oprisk.server.behavior.PrimaryBusinessObject
import org.json.JSONObject
import org.json.JSONArray
import com.sas.oprisk.server.CustomObject
import com.sas.oprisk.server.CustomObject101
import com.sas.oprisk.server.ApplicationProperties
import com.sas.oprisk.framework.server.util.DateTimeUtils;
import com.sas.oprisk.server.LinkInstance;
import com.sas.oprisk.server.logical.DimensionalAreaHandle;
import com.sas.oprisk.server.logical.DimensionalArea;

@FunctionDescription("Функция создает Бланки ответов по данным из спредшита")
@FunctionExamples([
        @FunctionExample(code = "<set name=\"TEMP.res\" value=\"C_CreateASFromSpreadsheet('GRC','asmt_assessor', 'GRC', 'asmt_risk', co1_func_data, location, ToString(co100_assessmentType))\"/>"),
])
class CreateASFromSpreadsheet extends AFunction {
    private static Log log = LogFactory.getLog(CreateASFromSpreadsheet.class);

    static Set<Long> getRks(String linkTypeCd, String linkTypeNm, PersistenceSession psession, Context uiContext) throws Exception {
        LinkType linkTypeAsr = LinkType.object.fetchByExternalReference(linkTypeCd, linkTypeNm, psession);
        Set<Long> pboRks = MonitorUIContextUtil.getLinkedObjectRksForLinkType(uiContext, linkTypeAsr.getLinkTypeRk());
        return pboRks;
    }

    ArrayList<PrimaryBusinessObject> getLinkedObjects(PrimaryBusinessObject currentObject, LinkType linkType, PersistenceSession psession) {
        ArrayList<PrimaryBusinessObject> result = new ArrayList<PrimaryBusinessObject>();
        if (currentObject != null) {
            Set<LinkInstance> linkInstances =  currentObject.getLinkInstancesForLinkType(linkType.getKey(), psession)
            for (LinkInstance linkInstance : linkInstances) {
                PrimaryBusinessObject linkedObject;
                if (linkInstance.getBusinessObjectRk1() == currentObject.getKey()) {
                    BusinessObjectTypeInfo info = linkType.getBusinessObjectTypeInfo2();
                    linkedObject = (PrimaryBusinessObject)info.getClassObject().fetch(linkInstance.getBusinessObjectRk2(), psession);
                } else {
                    BusinessObjectTypeInfo info = linkType.getBusinessObjectTypeInfo1();
                    linkedObject = (PrimaryBusinessObject)info.getClassObject().fetch(linkInstance.getBusinessObjectRk1(), psession);
                }
                result.add(linkedObject)
            }
        }
        return result
    }

    void createLink(String linkTypeSourceSystemCd, String linkTypeId, Long r_ObjectKey, Long l_ObjectKey, PersistenceSession psession, Context context) {
        LinkType linkType = LinkType.object.fetchByExternalReference(linkTypeSourceSystemCd, linkTypeId, psession);
        if (null == LinkInstance.object.fetchIfExistsByLinkInstanceAkey(r_ObjectKey, l_ObjectKey, linkType.getKey(), psession)) {
            LinkInstance li = LinkInstance.object.create(psession);
            JSONObject linkInsJSON = MonitorUIContextUtil.createLinkInstanceJSON(li, r_ObjectKey, l_ObjectKey, linkType.getKey());
            JSONArray newLinks = new JSONArray();
            newLinks.put(linkInsJSON);
            MonitorUIContextUtil.addObjectLinks(context, newLinks);
        }
    }

    @Override
    Object evaluate(Object... args) throws EvaluationException {
        PersistenceSession psession = ThreadLocalPersistenceSession.getLocalPersistenceSession();
        Context uiContext = (Context)this.getContext();

        CustomObject currentObject = (CustomObject)MonitorUIContextUtil.getBusinessObject(uiContext);

        //**** Смотрим в связь с оценщиками ****//
        Set<Long> assessorRks = getRks((String)args[0], (String)args[1], psession, uiContext);
        Map<Long, User> assessors = User.object.getUsers(assessorRks, psession);

        //**** Смотрим в связь с оцениваемыми объектами ****//
        LinkType linkTypeObj = LinkType.object.fetchByExternalReference((String)args[2], (String)args[3], psession);
        Set<Long> objRks = MonitorUIContextUtil.getLinkedObjectRksForLinkType(uiContext, linkTypeObj.getLinkTypeRk());

        //**** Смотрим в связь с бланками ответов ****//
        LinkType linkTypeAnSht = LinkType.object.fetchByExternalReference("GRC", "asmt_anSht", psession);
        Set<Long> anShtRks = MonitorUIContextUtil.getLinkedObjectRksForLinkType(uiContext, linkTypeAnSht.getLinkTypeRk());

        //**** Считываем данные из спредшита ****//
        String data = (String)args[4]
        JSONObject jsonData = new JSONObject(data);
        JSONArray jsonArr = jsonData.get("items");
        Long curObjKey = 0;
        HashSet<String> newAssessors = new HashSet<String>();
        

        String handleString = (String)args[5];
        DimensionalAreaHandle areaHandle = DimensionalAreaHandle.create(handleString);
        DimensionalArea area = DimensionalArea.object.fetch(areaHandle, psession);

        Set<Long> objectsToDelete = new HashSet<>(anShtRks);
        objectsToDelete.clear();

        String assessmentType = (String)args[6];
        Set<Long> assessorsAdded = new HashSet<Long>();
        for (int i=0; i < jsonArr.length(); i++) {
            JSONObject curJsonObj = jsonArr.getJSONObject(i);
            String objectName = curJsonObj.get("Object");
            boolean anShtNotCreated = true;
            Long existingAnSht = null;
            objRks.each {rk ->
                BusinessObjectTypeInfo info = linkTypeObj.getBusinessObjectTypeInfo2();
                PrimaryBusinessObject pbo = (PrimaryBusinessObject)info.getClassObject().fetch(rk, psession);
                //**** Запоминаем какой риск сейчас мы рассматриваем ****//
                if (pbo.getName() == objectName) {
                    curObjKey = pbo.getKey();
                }
            }
            ArrayList<User> assessorsArr = new ArrayList<String>();
            for(User assessor : assessors.values()) {
                //**** Смотрим есть ли уже такая оценка риска в связи ****//

                if (assessmentType != 'CTL') {
                    existingAnSht = null;
                    anShtNotCreated = true;
                }
                anShtRks.each { rk ->
                    BusinessObjectTypeInfo info = linkTypeAnSht.getBusinessObjectTypeInfo2();
                    PrimaryBusinessObject pbo = (PrimaryBusinessObject)info.getClassObject().fetch(rk, psession);
                    if (pbo.getCustStringFieldValue("x_co101_objKey", psession) == String.valueOf(curObjKey) && pbo.getCustStringFieldValue("x_co101_assessorId", psession).contains(assessor.getUserId())) {
                        log.warn(pbo.getCustStringFieldValue("x_co101_objKey", psession))
                        anShtNotCreated = false;
                        existingAnSht = rk;
                    }
                }
                if (curJsonObj.get(assessor.getName()) == "true_r") {
                    //**** Создаем Бланк ответов и заполняем необходимые поля ****//
                    if (assessmentType == 'CTL') {
                        assessorsArr.add(assessor)
                        continue;
                    }
                    if (anShtNotCreated) {
                        CustomObject101 answerSheet = CustomObject101.object.create(psession);
                        answerSheet.setLocation(area, psession);
                        answerSheet.setCustomUser1(assessor);
                        answerSheet.setCustObjNm(objectName);
                        answerSheet.setCustStringFieldValue("x_co101_assessorId", assessor.getUserId(), psession)
                        answerSheet.setCustStringFieldValue("x_co101_objKey", String.valueOf(curObjKey), psession)
                        answerSheet.setCreator(SecurityUtils.getUser(psession));
                        answerSheet.setCreatedDttm(DateTimeUtils.now());
                        answerSheet.setUpdater(SecurityUtils.getUser(psession));
                        answerSheet.setCustStringFieldValue("x_co101_assesType", 'risk', psession);
                        answerSheet.save(ApplicationProperties.getInitialSaveReasonTxt(), psession);
                        psession.commit();

                        //**** Создаем связь Оценка - Бланк Ответов ****//
                        createLink("GRC", "asmt_anSht", currentObject.getKey(), answerSheet.getKey(), psession, uiContext);
                        //**** Создаем связь Бланк Ответов - Риск ****//
                        createLink("GRC", "anSht_risk", answerSheet.getKey(), curObjKey, psession, uiContext);
                        //**** Создаем связь Бланк Ответов - Оценщик****//
                        createLink("GRC", "anSht_assessor", answerSheet.getKey(), assessor.getKey(), psession, uiContext);

                        createLink("GRC", "asmt_assessor_tmp", currentObject.getKey(), assessor.getKey(), psession, uiContext);
                        
                    }
                } else {
                    if (!anShtNotCreated && assessmentType != 'CTL') {
                        //** DELETE **//
                        objectsToDelete.add(existingAnSht);
                    }                    
                }
            }
            
            
            log.warn(anShtNotCreated)
            if (assessmentType == 'CTL') {

            }
            if (assessmentType == 'CTL' && anShtNotCreated) {
                CustomObject101 answerSheet = CustomObject101.object.create(psession);
                answerSheet.setLocation(area, psession);
                answerSheet.setCustObjNm(objectName);
                String assessorString = '';
                for (User assessor : assessorsArr) {
                    assessorString += assessor.getUserId() + ','
                }
                answerSheet.setCustStringFieldValue("x_co101_assessorId", assessorString, psession)
                answerSheet.setCustStringFieldValue("x_co101_objKey", String.valueOf(curObjKey), psession)
                answerSheet.setCreator(SecurityUtils.getUser(psession));
                answerSheet.setCreatedDttm(DateTimeUtils.now());
                answerSheet.setUpdater(SecurityUtils.getUser(psession));
                answerSheet.setCustStringFieldValue("x_co101_assesType", 'control', psession);
                answerSheet.save(ApplicationProperties.getInitialSaveReasonTxt(), psession);
                psession.commit();

                //**** Создаем связь Оценка - Бланк Ответов ****//
                createLink("GRC", "asmt_anSht", currentObject.getKey(), answerSheet.getKey(), psession, uiContext);
                
                //**** Создаем связь Бланк Ответов - Оценщик****//
                for (User assessor : assessorsArr) {
                    createLink("GRC", "anSht_assessor", answerSheet.getKey(), assessor.getKey(), psession, uiContext);
                    if (!assessorsAdded.contains(assessor.getKey())) {
                        createLink("GRC", "asmt_assessor_tmp", currentObject.getKey(), assessor.getKey(), psession, uiContext);
                        assessorsAdded.add(assessor.getKey());
                    }
                }
            } else {
                if (assessmentType == 'CTL' && !anShtNotCreated) {
                    if (assessorsArr.size() == 0) {
                        objectsToDelete.add(existingAnSht);
                    } else {
                        CustomObject currentObject1 = (CustomObject)CustomObject101.object.fetchIfExists(existingAnSht, psession)
                        LinkType linkTypeAssAnsht = LinkType.object.fetchByExternalReference("GRC", "anSht_assessor", psession);
                        ArrayList<PrimaryBusinessObject> assessors_ansht = getLinkedObjects((PrimaryBusinessObject)currentObject1, linkTypeAssAnsht, psession);

                        LinkType linkTypeAssessors
                        Set<LinkInstance> linkInstances
                        for(User assessor : assessors_ansht) {
                            if (!assessorsArr.contains(assessor)) {
                                
                                linkTypeAssessors = LinkType.object.fetchByExternalReference("GRC", "anSht_assessor", psession);
                                linkInstances =  currentObject1.getLinkInstancesForLinkType(linkTypeAssessors.getKey(), psession)
                                for (LinkInstance linkInstance : linkInstances) {
                                    if (linkInstance.getBusinessObjectRk2() == assessor.getKey()) {
                                        linkInstance.delete("reason", psession);
                                    }
                                }
                            }
                        }

                        for (PrimaryBusinessObject assessor : assessorsArr) {
                            if (!assessors_ansht.contains(assessor)) {
                                createLink("GRC", "anSht_assessor", existingAnSht, assessor.getKey(), psession, uiContext);
                                linkTypeAssessors = LinkType.object.fetchByExternalReference("GRC", "asmt_assessor_tmp", psession);
                                linkInstances =  currentObject1.getLinkInstancesForLinkType(linkTypeAssessors.getKey(), psession);
                                boolean isAdded = false
                                for (LinkInstance linkInstance : linkInstances) {
                                    if (linkInstance.getBusinessObjectRk2() == assessor.getKey()) {
                                        isAdded = true
                                    }
                                }
                                if (!isAdded) {
                                    createLink("GRC", "asmt_assessor_tmp", currentObject.getKey(), assessor.getKey(), psession, uiContext);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (assessmentType == 'CTL') {
            anShtRks.each { rk ->
                boolean needsDeleating = true
                BusinessObjectTypeInfo info = linkTypeAnSht.getBusinessObjectTypeInfo2();
                PrimaryBusinessObject pbo = (PrimaryBusinessObject)info.getClassObject().fetch(rk, psession);
                for (int i=0; i < jsonArr.length(); i++) {
                    JSONObject curJsonObj = jsonArr.getJSONObject(i);
                    String objectName = curJsonObj.get("Object");
                    if (objectName == pbo.getName()) {
                        needsDeleating = false
                    }
                }
                if (needsDeleating) {
                    objectsToDelete.add(rk)
                }
            }
        }
        objectsToDelete.each { rk ->
            BusinessObjectTypeInfo info = linkTypeAnSht.getBusinessObjectTypeInfo2();
            PrimaryBusinessObject pbo = (PrimaryBusinessObject)info.getClassObject().fetch(rk, psession);
            pbo.delete("reason", psession);
        }
    }

}
