package com.adesa.attendance.vendor;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class VendorRules {
 private VendorRules(){}
 public static final String VERSION="vendor-acquisition-v1";
 public static final String PRIVACY="Personal information provided in this Form will be processed by Adesa Holdings for vendor onboarding, due diligence, verification, procurement, payment administration and compliance purposes, in accordance with applicable data protection laws and Adesa Holdings’ data protection requirements.";
 public static final String DECLARATION="I confirm that the information given in this form is true and complete. I will tell Adesa Holdings promptly of any change. I accept that Adesa Holdings may verify this information, and may decline or end the relationship if any of it is found to be false. I have read the Data Protection Notice above.";
 public static final String CONSENT="I consent to the use of my selected or uploaded electronic signature for this vendor acquisition application. I confirm that I am authorised to sign on behalf of my business and intend this electronic signature to represent my acceptance of the declaration.";
 public static final String CONFLICT="Does any director, officer, employee, agent or person connected with Adesa Holdings or any Adesa Group entity have any direct or indirect financial, personal or other interest in the Vendor?";
 public static final List<String> SITES=List.of("HOLDINGS","ENERGY","MEDIA","SERVICES","DISTRIBUTION");
 public static final List<String> CATEGORIES=List.of("Electronics and components","Phone and device parts","Accessories","IT and software services","Logistics and delivery","Office supplies and equipment","Professional services","Facilities and maintenance","Energy and engineering services","Media, printing and advertising","Other");
 public static final List<String> DOCS=List.of("CAC certificate","Tax clearance certificate","Company profile","Price list or quotation","Relevant licences or certifications","Other supporting documents","Trade references","Director identification","Insurance certificate","Proof of address");
 public static final Set<String> STATUSES=Set.of("PENDING_REVIEW","UNDER_REVIEW","ADDITIONAL_INFORMATION_REQUIRED","APPROVED","REJECTED","SUSPENDED","ARCHIVED");
 public static String label(String site){return site.equals("HOLDINGS")?"ADESA Holdings":"ADESA "+site.substring(0,1)+site.substring(1).toLowerCase(Locale.ROOT);}
 public static void check(boolean valid,String message){if(!valid)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 public static String text(Object value,int max){String s=value==null?"":String.valueOf(value).strip();check(s.length()<=max,"A response is too long.");check(s.codePoints().noneMatch(c->c==0||c<9),"Invalid characters in response.");return s;}
 public static String required(Map<String,Object> data,String key,int max){String s=text(data.get(key),max);check(!s.isBlank(),"Complete the required field: "+key);return s;}
 public static String normal(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
 public static String email(Object value){String s=text(value,254).toLowerCase(Locale.ROOT);check(s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),"Enter a valid contact email.");return s;}
 public static String phone(Object value){String s=text(value,40).replaceAll("[^0-9+]","");if(s.matches("0[0-9]{10}"))s="+234"+s.substring(1);if(s.matches("234[0-9]{10}"))s="+"+s;check(s.matches("\\+[1-9][0-9]{7,14}"),"Use a valid international contact phone number.");return s;}
 public static Map<String,Object> validate(Map<String,Object> raw){var d=new LinkedHashMap<String,Object>();for(String key:List.of("businessName","businessType","businessTypeOther","address","cac","tin","website","contactName","contactPosition","categoryOther","description","coverage","paymentTerms","paymentOther","leadDays","warranty","bankName","accountName","accountNumber","bankFormat","bankFormatDetails","conflictDetails","signatoryName","signatoryPosition","declarationDate","signatureMethod","signatureStyle"))d.put(key,text(raw.get(key),Set.of("description","warranty","conflictDetails","address").contains(key)?5000:300));
 for(String key:List.of("businessName","businessType","address","contactName","description","signatoryName","signatoryPosition","declarationDate","signatureMethod"))required(d,key,5000);
 check(Set.of("Sole proprietorship","Partnership","Limited company","Other").contains(d.get("businessType")),"Select a business type.");if("Other".equals(d.get("businessType")))required(d,"businessTypeOther",300);
 d.put("contactEmail",email(raw.get("contactEmail")));d.put("contactPhone",phone(raw.get("contactPhone")));
 check(raw.get("categories") instanceof List<?> c&&!c.isEmpty()&&c.size()<=CATEGORIES.size()&&CATEGORIES.containsAll(c),"Select valid vendor categories.");d.put("categories",new ArrayList<>(new LinkedHashSet<>((List<?>)raw.get("categories"))));if(((List<?>)d.get("categories")).contains("Other"))required(d,"categoryOther",300);
 String payment=String.valueOf(d.get("paymentTerms"));check(payment.isBlank()||Set.of("Cash on delivery","Net 7 days","Net 14 days","Net 30 days","Other").contains(payment),"Select valid payment terms.");if(payment.equals("Other"))required(d,"paymentOther",300);
 if(!String.valueOf(d.get("leadDays")).isBlank())check(String.valueOf(d.get("leadDays")).matches("[0-9]{1,4}"),"Lead time must be whole days.");
 String website=String.valueOf(d.get("website"));check(website.isBlank()||website.matches("https?://[^\\s]+"),"Use a complete website address.");
 boolean bank=List.of("bankName","accountName","accountNumber").stream().anyMatch(k->!String.valueOf(d.get(k)).isBlank());if(bank){for(String k:List.of("bankName","accountName","accountNumber"))required(d,k,300);String account=String.valueOf(d.get("accountNumber"));if("ALTERNATIVE".equals(d.get("bankFormat"))){check(account.matches("[A-Za-z0-9 -]{4,34}"),"Enter a valid alternative account format.");required(d,"bankFormatDetails",300);}else{check(account.matches("[0-9]{10}"),"Nigerian account numbers must contain exactly 10 digits.");d.put("bankFormat","NIGERIAN");}}
 check(Set.of("Yes","No").contains(raw.get("conflict")),"Answer the conflict of interest question.");d.put("conflict",raw.get("conflict"));if("Yes".equals(raw.get("conflict")))required(d,"conflictDetails",5000);
 check(Boolean.TRUE.equals(raw.get("declarationAccepted"))&&Boolean.TRUE.equals(raw.get("signatureConsent")),"Explicitly accept the declaration and electronic signature consent.");check(VERSION.equals(raw.get("formVersion")),"The form has changed. Reopen your invitation.");check(Set.of("TYPED","UPLOADED").contains(d.get("signatureMethod")),"Select a signature method.");if("TYPED".equals(d.get("signatureMethod")))check(Set.of("CLASSIC","FLOW","BOLD").contains(d.get("signatureStyle")),"Select a signature style.");
 LocalDate date;try{date=LocalDate.parse(String.valueOf(d.get("declarationDate")));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose the declaration date.");}check(!date.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))&&!date.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(30)),"Choose a recent declaration date.");
 d.put("declarationAccepted",true);d.put("signatureConsent",true);d.put("declarationWording",DECLARATION);d.put("signatureConsentWording",CONSENT);d.put("privacyWording",PRIVACY);d.put("conflictQuestion",CONFLICT);d.put("formVersion",VERSION);return d;
 }
}
