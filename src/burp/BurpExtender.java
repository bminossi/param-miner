package burp;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

import com.google.gson.*;
import com.google.gson.internal.LinkedTreeMap;
import org.apache.commons.lang3.StringEscapeUtils;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;

public class BurpExtender implements IBurpExtender {
    private static final String name = "Backslash Powered Scanner";
    private static final String version = "0.91";

    @Override
    public void registerExtenderCallbacks(final IBurpExtenderCallbacks callbacks) {

        new Utilities(callbacks);
        callbacks.setExtensionName(name);


        try {
            StringUtils.isNumeric("1");
        } catch (java.lang.NoClassDefFoundError e) {
            Utilities.out("Failed to import the Apache Commons Lang library. You can get it from http://commons.apache.org/proper/commons-lang/");
            throw new NoClassDefFoundError();
        }

        try {
            callbacks.getHelpers().analyzeResponseVariations();
        } catch (java.lang.NoSuchMethodError e) {
            Utilities.out("This extension requires Burp Suite Pro 1.7.10 or later");
            throw new NoSuchMethodError();
        }

        FastScan scan = new FastScan(callbacks);
        callbacks.registerScannerCheck(scan);
        callbacks.registerExtensionStateListener(scan);
        callbacks.registerContextMenuFactory(new OfferParamGuess(callbacks));

        Utilities.out("Loaded " + name + " v" + version);
        Utilities.out("Debug mode: " + Utilities.DEBUG);
        Utilities.out("Thorough mode: " + Utilities.THOROUGH_MODE);
        Utilities.out("Input transformation detection: " + Utilities.TRANSFORMATION_SCAN);
        Utilities.out("Suspicious input handling detection: " + Utilities.DIFFING_SCAN);
        Utilities.out("    TRY_SYNTAX_ATTACKS "+Utilities.TRY_SYNTAX_ATTACKS);
        Utilities.out("    TRY_VALUE_PRESERVING_ATTACKS "+Utilities.TRY_VALUE_PRESERVING_ATTACKS);
        Utilities.out("    TRY_EXPERIMENTAL_CONCAT_ATTACKS "+Utilities.TRY_EXPERIMENTAL_CONCAT_ATTACKS);
        Utilities.out("    TRY_HPP "+Utilities.TRY_HPP);
        Utilities.out("    TRY_HPP_FOLLOWUP "+Utilities.TRY_HPP_FOLLOWUP);
        Utilities.out("    TRY_MAGIC_VALUE_ATTACKS "+Utilities.TRY_MAGIC_VALUE_ATTACKS);

    }


}

class FastScan implements IScannerCheck, IExtensionStateListener {
    private TransformationScan transformationScan;
    private DiffingScan diffingScan;
    private IExtensionHelpers helpers;
    private IBurpExtenderCallbacks callbacks;

    FastScan(final IBurpExtenderCallbacks callbacks) {
        transformationScan = new TransformationScan(callbacks);
        diffingScan = new DiffingScan();
        this.callbacks = callbacks;
        helpers = callbacks.getHelpers();
    }

    public void extensionUnloaded() {
        Utilities.out("Unloading extension...");
        Utilities.unloaded.set(true);
    }

    private IParameter getParameterFromInsertionPoint(IScannerInsertionPoint insertionPoint, byte[] request) {
        IParameter baseParam = null;
        int basePayloadStart = insertionPoint.getPayloadOffsets("x".getBytes())[0];
        List<IParameter> params = helpers.analyzeRequest(request).getParameters();
        for (IParameter param : params) {
            if (param.getValueStart() == basePayloadStart && insertionPoint.getBaseValue().equals(param.getValue())) {
                baseParam = param;
                break;
            }
        }
        return baseParam;
    }

    public List<IScanIssue> doActiveScan(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {

        ArrayList<IScanIssue> issues = new ArrayList<>();
        if(!(Utilities.TRANSFORMATION_SCAN || Utilities.DIFFING_SCAN)) {
            Utilities.out("Aborting scan - all scanner checks disabled");
            return issues;
        }

        // make a custom insertion point to avoid burp excessively URL-encoding payloads
        IParameter baseParam = getParameterFromInsertionPoint(insertionPoint, baseRequestResponse.getRequest());
        if (baseParam != null && (baseParam.getType() == IParameter.PARAM_BODY || baseParam.getType() == IParameter.PARAM_URL)) {
            insertionPoint = new ParamInsertionPoint(baseRequestResponse.getRequest(), baseParam.getName(), baseParam.getValue(), baseParam.getType());
        }

        if (Utilities.TRANSFORMATION_SCAN) {
            issues.add(transformationScan.findTransformationIssues(baseRequestResponse, insertionPoint));
        }

        if (Utilities.DIFFING_SCAN) {
            issues.add(diffingScan.findReflectionIssues(baseRequestResponse, insertionPoint));
        }

        if (baseParam != null && (baseParam.getType() == IParameter.PARAM_BODY || baseParam.getType() == IParameter.PARAM_URL) && Utilities.getExtension(baseRequestResponse.getRequest()).equals(".php")) {
            String param_name = baseParam.getName() + "[]";
            byte[] newReq = helpers.removeParameter(baseRequestResponse.getRequest(), baseParam);
            IParameter newParam = helpers.buildParameter(param_name, baseParam.getValue(), baseParam.getType());
            newReq = helpers.addParameter(newReq, helpers.buildParameter(param_name, "", baseParam.getType()));
            newReq = helpers.addParameter(newReq, newParam);

            IScannerInsertionPoint arrayInsertionPoint = new ParamInsertionPoint(newReq, param_name, newParam.getValue(), newParam.getType());
            IHttpRequestResponse newBase = callbacks.makeHttpRequest(baseRequestResponse.getHttpService(), arrayInsertionPoint.buildRequest(newParam.getValue().getBytes()));

            if (Utilities.TRANSFORMATION_SCAN) {
                issues.add(transformationScan.findTransformationIssues(newBase, arrayInsertionPoint));
            }

            if (Utilities.DIFFING_SCAN) {
                issues.add(diffingScan.findReflectionIssues(newBase, arrayInsertionPoint));
            }
        }

        return issues
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        return new ArrayList<>();

    }

    @Override
    public int consolidateDuplicateIssues(IScanIssue existingIssue, IScanIssue newIssue) {
        if (existingIssue.getIssueName().equals(newIssue.getIssueName()) && existingIssue.getIssueDetail().equals(newIssue.getIssueDetail()))
            return -1;
        else return 0;
    }
}


class Fuzzable extends CustomScanIssue {
    private final static String NAME = "Interesting input handling: ";
    private final static String DETAIL = "The application reacts to inputs in a way that suggests it might be vulnerable to some kind of server-side code injection. The probes are listed below in chronological order, with evidence. Response attributes that only stay consistent in one probe-set are italicised, with the variable attribute starred.";
    private final static String REMEDIATION = "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. Try to determine the root cause of the observed behaviour." +
            "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results. ";

    Fuzzable(IHttpRequestResponse[] requests, URL url, String title, String detail, boolean reliable, String severity) {
        super(requests[0].getHttpService(), url, requests, NAME + title, DETAIL + detail, severity, calculateConfidence(reliable), REMEDIATION);
    }

    private static String calculateConfidence(boolean reliable) {
        String confidence = "Tentative";
        if (reliable) {
            confidence = "Firm";
        }
        return confidence;
    }

}

class InputTransformation extends CustomScanIssue {
    private final static String NAME = "Suspicious input transformation";
    private final static String DETAIL = "The application transforms input in a way that suggests it might be vulnerable to some kind of server-side code injection";
    private final static String REMEDIATION =
            "This issue does not necessarily indicate a vulnerability; it is merely highlighting behaviour worthy of manual investigation. " +
                    "Try to determine the root cause of the observed input transformations. " +
                    "Refer to <a href='http://blog.portswigger.net/2016/11/backslash-powered-scanning-hunting.html'>Backslash Powered Scanning</a> for further details and guidance interpreting results.";
    private final static String CONFIDENCE = "Tentative";

    InputTransformation(ArrayList<String> interesting, ArrayList<String> boring, IHttpRequestResponse base, URL url, String paramName) {
        super(base.getHttpService(), url, new IHttpRequestResponse[]{base}, NAME, generateDetail(interesting, boring, paramName), generateSeverity(interesting), CONFIDENCE, REMEDIATION);
    }

    private static String generateSeverity(ArrayList<String> interesting) {
        String severity = "High";
        if (interesting.size() == 1 && interesting.contains("\\0 => \0")) {
            severity = "Information";
        }
        return severity;
    }

    private static String generateDetail(ArrayList<String> interesting, ArrayList<String> boring, String paramName) {
        String details = DETAIL + "<br/><br/>Affected parameter:<code>" + StringEscapeUtils.escapeHtml4(paramName) + "</code><br/><br/>";
        details += "<p>Interesting transformations:</p><ul> ";
        for (String transform : interesting) {
            details += "<li><b><code style='font-size: 125%;'>" + StringEscapeUtils.escapeHtml4(transform) + "</code></b></li>";
        }
        details += "</ul><p>Boring transformations:</p><ul>";
        for (String transform : boring) {
            details += "<li><b><code>" + StringEscapeUtils.escapeHtml4(transform) + "</code></b></li>";
        }
        details += "</ul>";
        return details;
    }
}

class CustomScanIssue implements IScanIssue {
    private IHttpService httpService;
    private URL url;
    private IHttpRequestResponse[] httpMessages;
    private String name;
    private String detail;
    private String severity;
    private String confidence;
    private String remediation;

    CustomScanIssue(
            IHttpService httpService,
            URL url,
            IHttpRequestResponse[] httpMessages,
            String name,
            String detail,
            String severity,
            String confidence,
            String remediation) {
        this.name = name;
        this.detail = detail;
        this.severity = severity;
        this.httpService = httpService;
        this.url = url;
        this.httpMessages = httpMessages;
        this.confidence = confidence;
        this.remediation = remediation;
    }

    CustomScanIssue(
            IHttpService httpService,
            URL url,
            IHttpRequestResponse httpMessages,
            String name,
            String detail,
            String severity,
            String confidence,
            String remediation) {
        this.name = name;
        this.detail = detail;
        this.severity = severity;
        this.httpService = httpService;
        this.url = url;
        this.httpMessages = new IHttpRequestResponse[1];
        this.httpMessages[0] = httpMessages;

        this.confidence = confidence;
        this.remediation = remediation;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public String getIssueName() {
        return name;
    }

    @Override
    public int getIssueType() {
        return 0;
    }

    @Override
    public String getSeverity() {
        return severity;
    }

    @Override
    public String getConfidence() {
        return confidence;
    }

    @Override
    public String getIssueBackground() {
        return null;
    }

    @Override
    public String getRemediationBackground() {
        return null;
    }

    @Override
    public String getIssueDetail() {
        return detail;
    }

    @Override
    public String getRemediationDetail() {
        return remediation;
    }

    @Override
    public IHttpRequestResponse[] getHttpMessages() {
        return httpMessages;
    }

    @Override
    public IHttpService getHttpService() {
        return httpService;
    }

    public String getHost() {
        return null;
    }

    public int getPort() {
        return 0;
    }

    public String getProtocol() {
        return null;
    }
}



class ParamInsertionPoint implements IScannerInsertionPoint {
    byte[] request;
    String name;
    String value;
    byte type;

    ParamInsertionPoint(byte[] request, String name, String value, byte type) {
        this.request = request;
        this.name = name;
        this.value = value;
        this.type = type;
    }

    @Override
    public String getInsertionPointName() {
        return name;
    }

    @Override
    public String getBaseValue() {
        return value;
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(Utilities.helpers.bytesToString(payload)), type);
        return Utilities.helpers.updateParameter(request, newParam);
    }

    @Override
    public int[] getPayloadOffsets(byte[] payload) {
        //IParameter newParam = Utilities.helpers.buildParameter(name, Utilities.encodeParam(Utilities.helpers.bytesToString(payload)), type);
        return new int[]{0, 0};
        //return new int[]{newParam.getValueStart(), newParam.getValueEnd()};
    }

    @Override
    public byte getInsertionPointType() {
        return type;
        //return IScannerInsertionPoint.INS_PARAM_BODY;
        // return IScannerInsertionPoint.INS_EXTENSION_PROVIDED;
    }
}

class ParamNameInsertionPoint extends ParamInsertionPoint {

    public ParamNameInsertionPoint(byte[] request, String name, String value, byte type) {
        super(request, name, value, type);
    }

    @Override
    public byte[] buildRequest(byte[] payload) {
        IParameter newParam = Utilities.helpers.buildParameter(Utilities.helpers.bytesToString(payload), Utilities.encodeParam(value), type);
        return Utilities.helpers.updateParameter(request, newParam);
    }
}

class JsonParamNameInsertionPoint extends ParamInsertionPoint {
    byte[] headers;
    byte[] body;
    HashMap base;

    public JsonParamNameInsertionPoint(byte[] request, String name, String value, byte type) {
        super(request, name, value, type); // Utilities.encodeJSON(value)
        int start = Utilities.getBodyStart(request);
        headers = Arrays.copyOfRange(request, 0, start);
        body = Arrays.copyOfRange(request, start, request.length);
        base = new GsonBuilder().create().fromJson(Utilities.helpers.bytesToString(body), HashMap.class);
    }

    private Object makeNode(String nextKey) {
        if (nextKey.matches("\\d+")) {
            return new Object[1];
        }
        else {
            return new LinkedTreeMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Object replicateNode(Object baseNode, String nextKey) {
        if (nextKey.matches("\\d+")) {
            Object[] replacementArray = ((Object[]) baseNode).clone();
            return replacementArray;
        }
        else {
            LinkedTreeMap replacementMap = new LinkedTreeMap();
            replacementMap.putAll((Map) baseNode);
            return replacementMap;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] buildRequest(byte[] payload)  {

        ArrayList<String> keys = new ArrayList<>(Arrays.asList(Utilities.helpers.bytesToString(payload).split(":")));
        String finalKey = keys.get(keys.size()-1);
        //keys.remove(finalKey);
        //keys.remove("");

        LinkedTreeMap resultMap = new LinkedTreeMap();
        resultMap.putAll(base);
        Object next = resultMap;


        for (int i = 0; i < keys.size() - 1; i++) {

            // find the next injection point
            // either create it
            // or duplicate it
            String key = keys.get(i);
            String nextKey = keys.get(i + 1);

            if (key.matches("\\d+")) {
                int index = Integer.parseInt(key);
                Object[] injectionPoint = (Object[]) next;
                if (injectionPoint[index] != null) {
                    injectionPoint[index] = replicateNode(injectionPoint[index], nextKey);
                }
                else {
                    injectionPoint[index] = makeNode(nextKey);
                }
                next = injectionPoint[index];
            } else {
                LinkedTreeMap injectionPoint = (LinkedTreeMap) next;
                if (injectionPoint.containsKey(key)) {
                    injectionPoint.put(key, replicateNode(injectionPoint.get(key), nextKey));
                } else {
                    injectionPoint.put(key, makeNode(nextKey));
                }
                next = injectionPoint.get(key);
            }
        }
        LinkedTreeMap<String, String> ohdear = (LinkedTreeMap) next;
        ohdear.put(finalKey, finalKey + value);

        String mergedJson = new GsonBuilder().create().toJson(resultMap);
        Utilities.out(mergedJson);
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write(headers);
            outputStream.write(Utilities.helpers.stringToBytes(mergedJson));
            return Utilities.fixContentLength(outputStream.toByteArray());
        }
        catch (IOException e) {
            throw new RuntimeException("Request creation unexpectedly failed");
        }
    }
}






