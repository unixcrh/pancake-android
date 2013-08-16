package com.imaginea.android.sugarcrm.restapi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.imaginea.android.sugarcrm.ModuleFields;
import com.imaginea.android.sugarcrm.rest.Rest;
import com.imaginea.android.sugarcrm.rest.SugarBean;

/**
 * ACLApiTest, tests the rest api calls
 * 
 * @author chander
 * 
 */
public class ACLRolesApiTest extends RestAPITest {
    String moduleName = "ACLRoles";

    String[] selectFields = { ModuleFields.ID, ModuleFields.NAME,
            ModuleFields.TYPE, ModuleFields.DESCRIPTION };

    HashMap<String, List<String>> linkNameToFieldsArray = new HashMap<String, List<String>>();

    public final static String LOG_TAG = ACLRolesApiTest.class.getSimpleName();

    String[] aclLinkFields = { ModuleFields.ID, ModuleFields.NAME, "category",
            "aclaccess", "acltype" };

    @SmallTest
    public void testACLList() throws Exception {
        final int offset = 0;
        final int maxResults = 10;

        linkNameToFieldsArray.put("actions", Arrays.asList(aclLinkFields));

        final SugarBean[] sBeans = getSugarBeans(offset, maxResults);
        // Its not necessary that there should always be ACL roles
        // assertTrue(sBeans.length > 0);

        if (Log.isLoggable(LOG_TAG, Log.INFO)) {
            for (final SugarBean sBean : sBeans) {
                Log.d(LOG_TAG, sBean.getBeanId());
                Log.d(LOG_TAG, sBean.getFieldValue(ModuleFields.NAME));
                Log.d(LOG_TAG, sBean.getFieldValue(ModuleFields.DESCRIPTION));
                printSugar(sBean.getRelationshipBeans("actions"), aclLinkFields);

            }
        }
    }

    void printSugar(final SugarBean[] sBeans, final String[] fieldsToPrint) {
        for (final SugarBean sBean : sBeans) {
            // Log.d(LOG_TAG, "" + sBean.getBeanId());
            for (int i = 0; i < fieldsToPrint.length; i++) {
                Log.d(LOG_TAG, sBean.getFieldValue(fieldsToPrint[i]));
            }
        }
    }

    /**
     * demonstrates the usage of RestUtil for contacts List. ModuleFields.NAME
     * or FULL_NAME is not returned by Sugar CRM. The fields that are not
     * returned by SugarCRM can be automated, but not yet generated
     * 
     * @param offset
     * @param maxResults
     * @return
     * @throws Exception
     */
    private SugarBean[] getSugarBeans(final int offset, final int maxResults)
            throws Exception {
        final String query = "", orderBy = "";

        final int deleted = 0;

        final SugarBean[] sBeans = Rest.getEntryList(url, mSessionId,
                moduleName, query, orderBy, offset + "", selectFields,
                linkNameToFieldsArray, maxResults + "", deleted + "");
        return sBeans;
    }

}
