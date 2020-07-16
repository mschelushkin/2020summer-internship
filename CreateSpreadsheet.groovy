package customfunctions.html5Ui

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
import com.sas.oprisk.server.CustomObject
import com.sas.oprisk.server.behavior.SudoPersistenceSessionWrapper
import org.json.JSONObject
import org.json.JSONArray
import com.sas.oprisk.server.LinkInstance;




@FunctionDescription("Функция создает JSON, необходимый для построения спредшита из двух LinkedBusinessObjects")
@FunctionReturnType("JSON")
@FunctionReturnDescription("Вовзращается JSON с данными и метаданными, необходимыми для создания")
@FunctionExamples([
        @FunctionExample(code = "<set name=\"TEMP.res\" value=\"C_CreateSpreadsheet('GRC','asmt_assessor', 'GRC', 'asmt_risk', co100_func_data, 'Риски', true, true)\" /> "),
])
class CreateSpreadsheet extends AFunction {
    private static Log log = LogFactory.getLog(CreateSpreadsheet.class);

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

    @Override
    Object evaluate(Object... args) throws EvaluationException {
        PersistenceSession psession = ThreadLocalPersistenceSession.getLocalPersistenceSession();
        PersistenceSession sudoPsession = new SudoPersistenceSessionWrapper(psession);
        Context uiContext = (Context) this.getContext();
        CustomObject currentObject = (CustomObject) MonitorUIContextUtil.getBusinessObject(uiContext);

        Set<Long> assessorRks = getRks((String)args[0], (String)args[1], psession, uiContext);
        Map<Long, User> assessors = User.object.getUsers(assessorRks, psession);

        String objectName = (String)args[5];
        String userNames = "{" +
                "\"rows\": [{\"cellOptions\": {}," +
                            "\"options\": {\"id\": \"new_row\"," +
                                        "\"quickSearch\": false," +
                                        "\"label\": \"name Row\"" +
                                        "}" +
                            "}]," +
                "\"columns\": [{\"cellOptions\": {\"readonly\": true," +
                                                "\"cellType\": \"string\"," +
                                                "}," +
                                "\"options\": {\"id\": \"Object\"," +
                                            "\"quickSearch\": false," +
                                            "\"label\": \"" + objectName + "\"" +
                                                "}" +
                                    "},";
        boolean isDescNeeded = (boolean)args[6];
        if(isDescNeeded) {
            userNames += "{\"cellOptions\": {" + 
                                            "\"readonly\": false," +
                                            "\"cellType\": \"dropdown\"," +
                                            "\"choices\":[" +
                                                        "{\"label\":\"useworkofothers\",\"value\":\"USEWORKOFOTHERS\"}," +
                                                        "{\"label\":\"ownwork\",\"value\":\"OWNWORK\"}," +
			                                        "]" +
                                        "}," +
                            "\"options\": {\"id\": \"Strategy\"," +
                                            "\"quickSearch\": false," +
                                            "\"label\": \"Стратегия тестирования\"" +
                                        "}" +
                        "},";
        }

        for(User assessor : assessors.values()) {
            userNames += "{\"cellOptions\": {\"readonly\": false," +
                                            "\"cellType\": \"dropdown\"," +
                                            "\"choices\": " +
                                            "[{" +
                                                "\"label\": \"Да\"," +
                                                "\"value\": \"true_r\"" +
                                            "}]" +
                                            "}," +
                            "\"options\": {\"id\": \"" + assessor.getName() + "\"," +
                                            "\"quickSearch\": false," +
                                            "\"label\": \""+assessor.getName()+"\"" +
                                        "}" +
                            "},";
        }
        userNames+="]," +
                "\"table\": {\"cellOptions\": {}," +
                            "\"options\": {\"selectionMode\": \"MultiToggle\"}" +
                            "}" +
                "}";
        String linkTypeCdObj = (String)args[2];
        String linkTypeNmObj = (String)args[3];
       
        boolean fillData = (boolean)args[7];
        LinkType linkTypeObj = LinkType.object.fetchByExternalReference(linkTypeCdObj, linkTypeNmObj, psession);
        Set<Long> pboRks = MonitorUIContextUtil.getLinkedObjectRksForLinkType(uiContext, linkTypeObj.getLinkTypeRk());
        String data = "{" +
                        "\"start\": 0," +
                        "\"limit\":"+pboRks.size()+"," +
                        "\"count\":"+pboRks.size()+"," +
                        "\"items\":[";
        int ind = 1;
        String value = "";

        pboRks.each { rk ->
            BusinessObjectTypeInfo info = linkTypeObj.getBusinessObjectTypeInfo2();
            PrimaryBusinessObject pbo = (PrimaryBusinessObject)info.getClassObject().fetch(rk, psession);
            data += "{"+
                    "\"key\": \"key"+ind+"\"," +
                    "\"Object\": \""+pbo.getCustObjNm()+"\",";
            if(isDescNeeded) {
                data += "\"Strategy\": \"OWNWORK\","
            }

            log.warn(assessors.values())

            for(User assessor : assessors.values()) {
                // Смотрим на предыдущий спредшит
                log.warn(assessor)
                try{
                    log.warn('TEST2')
                    String prev_data = (String)args[4]
                  	log.warn('prev_data ' + prev_data)
                    if(prev_data != null) {
                        JSONObject jsonPrevData = new JSONObject(prev_data);
                        JSONArray jsonPrevArr = jsonPrevData.get("items");
                        for (int i=0; i < jsonPrevArr.length(); i++) {
                            value = ""
                            JSONObject curJsonObj = jsonPrevArr.getJSONObject(i);
                            String objName = curJsonObj.get("Object");
                            if (curJsonObj.has(assessor.getName())) {
                                if (objName == pbo.getCustObjNm() && curJsonObj.get(assessor.getName()) == "true_r") {
                                    value = "true_r";
                                } else {
                                    value = "";
                                }
                            }
                        }
                    } else {
                        if(fillData) {
                            value = "true_r";    
                        } else {
                            value = "";
                        }
                    }
                } catch (Exception err) {
                    throw new EvaluationException(err);
                }

                // SOX SPREADSHEET
                LinkType linkTypeControlOwners = LinkType.object.fetchByExternalReference("GRC", "cntrl_owner", psession);
                ArrayList<PrimaryBusinessObject> cntrl_owners = getLinkedObjects(pbo, linkTypeControlOwners, psession);
                LinkType linkTypeControlUsers = LinkType.object.fetchByExternalReference("GRC", "cntrl_user", psession);
                ArrayList<PrimaryBusinessObject> cntrl_users = getLinkedObjects(pbo, linkTypeControlUsers, psession);

                for (PrimaryBusinessObject owner : cntrl_owners) {
                    if (assessor.getName() == owner.getName()) {value = "true_r"}
                }

                for (PrimaryBusinessObject user : cntrl_users) {
                    if (assessor.getName() == user.getName()) {value = "true_r"}
                }
                //

                data  += "\""+assessor.getName()+"\": \""+value+"\",";
            }
            data += "},";
            ind += 1;
        }
        data += "]}";

        String ans = "{\n"+
        "\"meta\":" + userNames +",\n" +
        "\"data\":" + data + "}";
        return ans;
    }
}
