package com.realanalytics.RealAnalytics.Policy;

import static com.realanalytics.RealAnalytics.Kafka.Streams.Utils.MATCH_POS;
import static com.realanalytics.RealAnalytics.Kafka.Streams.Utils.fetchFromParsedKey;
import static com.realanalytics.RealAnalytics.Kafka.Streams.Utils.parseKey;

import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toList;

import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realanalytics.RealAnalytics.Alerts.Alert;
import com.realanalytics.RealAnalytics.Alerts.StrmAlert;
import com.realanalytics.RealAnalytics.Alerts.Alert.AlertType;
import com.realanalytics.RealAnalytics.Alerts.Alert.Severity;
import com.realanalytics.RealAnalytics.Dao.AlertRepository;
import com.realanalytics.RealAnalytics.Dao.PipelineRepository;
import com.realanalytics.RealAnalytics.Dao.PolicyRepository;
import com.realanalytics.RealAnalytics.Dao.UserRepository;
import com.realanalytics.RealAnalytics.Data.StrmPolicy;
import com.realanalytics.RealAnalytics.Data.User;
import com.realanalytics.RealAnalytics.Kafka.KafkaConstants;
import com.realanalytics.RealAnalytics.Kafka.Serdes.RecordDeserializer;
import com.realanalytics.RealAnalytics.Notification.Notification;
import com.realanalytics.RealAnalytics.Notification.StrmNotification;
import com.realanalytics.RealAnalytics.Pipeline.Pipeline;
import com.realanalytics.RealAnalytics.Pipeline.Record;

@Service
public class PolicyProcessor {
	
	private static final Logger logger = 
            LoggerFactory.getLogger(PolicyProcessor.class);
	
	private long lastRefreshTime = 0;
	
	private long HIGH_THRESHOLD = 80;
	
	private long LOW_THRESHOLD = 30;
	
	@Value("${policy.refresh}")
	private long refreshPeriod ;
	
	private List<Policy> configuredPolicies;
	
	@Autowired
	private UserRepository usrrepo;
	
	@Autowired
	private PolicyRepository repo;
	
	@Autowired
	private AlertRepository alertRepo;
	
	@Autowired
	private PipelineRepository pRepo;
	
	@Autowired
	ObjectMapper mapper;
	
	
	public void handle(String key, String value) {
		logger.info("Notification Received:" + key);
		
		Record notifyRecord =
				new RecordDeserializer()
				.deserialize(KafkaConstants.NOTIF_TOPIC, 
						value.getBytes());
		
		if (notifyRecord == null) {
			logger.warn("Bad notify record");
		}
		
		checkPolicyRefresh();
		
		final StrmNotification notif = fetchNotification(key, notifyRecord);
		
		if (notif != null) {		
			List<Policy> applicablePolicies = configuredPolicies
									.stream()
									.filter(policy -> notif.getGroupedBy(policy.getMatchType()) != null)
									.collect(toList());
			
			if(applicablePolicies.size() > 0) {
				logger.info("Policies matched: " +  applicablePolicies.size());
				for(Policy policy: applicablePolicies) {
					logger.info("****" + policy.getPolicyName());
					apply(notif, policy);				
				}
			}
		}	
		
		logger.info("Processing ends");
	}


	private void apply(Notification notif, Policy policy) {
		if (policy instanceof StrmPolicy &&
				notif instanceof StrmNotification) {
			logger.info("Applying policy: " + policy.getPolicyName());
			applyStream((StrmNotification)notif, (StrmPolicy)policy);
		}	
	}


	private void applyStream(StrmNotification notif, StrmPolicy policy) {
		if (notif.getCount() == null) {
			logger.info("Count zero or unknown");
			return;
		}
		if (notif.getCount() < policy.getCount()) {
			logger.info("Count non zero but insignificant");
			return;
		}
		
		long diff = Math.abs(
						Math.subtractExact(
								policy.getCount(), 
								notif.getCount()));
		long percentVariation = Math.round((diff/policy.getCount()) * 100);
		
		Severity sev = computeSeverity(percentVariation);
		AlertType type = computeAlertType(notif);
		
		String username = "Unknown";
		String appname = "Unknown";
		
		User user = usrrepo.findById(notif.getActor());
		if (user != null) {
			username = user.getEmail();
			appname = user.getAppname();
		}
		StrmAlert newAlert = new StrmAlert(username,
								sev, 
								type, 
								policy.getPolicyId(), 
								policy.getPolicyName(),
								appname);
		
		logger.info("Alert generated " + sev.name() + ":" + type.name());
		
		alertRepo.save(newAlert);
	}


	private AlertType computeAlertType(StrmNotification notif) {
		if(notif.getGroupedBy("User") != null) {
			/*
			 *  if match by is just user and the count is high
			 *  then this is a failed login alert
			 */
			User user = usrrepo.findById(notif.getActor());
			return AlertType.FailedLogin;
		} else if (notif.getGroupedBy("Country") != null) {
			/*
			 * If event source is from any of these countries
			 * it is a risky login
			 */
			String country = notif.getGroupedBy("Country");
			if (country.equalsIgnoreCase("Afghanistan") 
					|| country.equalsIgnoreCase("Iran")
					|| country.equalsIgnoreCase("NorthKorea")) {
				return AlertType.RiskyLogin;
			} else {
				/*
				 * If the event is sourced from a different country
				 * than the one user is resident in then it signifies
				 * a proximity alert
				 */
				User user = usrrepo.findById(notif.getActor());
				if (user != null) {
					if (!user.getLocation().equalsIgnoreCase(country)) {
						return AlertType.Proximity;
					}
				} else {
					return AlertType.RiskyLogin;
				}
			}
		} else if (notif.getGroupedBy("Application") != null) {
			/*
			 * If the application is an unknown application
			 * then this could signify an attempt to DDOS
			 */
			String nullApplication = notif.getGroupedBy("Application");
			if (nullApplication.length() == 0 || nullApplication.equalsIgnoreCase("null"))
				return AlertType.DDOS;
		} else if (notif.getGroupedBy("Browser") != null) {
			/*
			 * Logins from mulitple browser
			 */
			return AlertType.SharedLogin;
		} else {
			return AlertType.Info;
		}
		return AlertType.Info;
	}


	private Severity computeSeverity(long percentVariation) {
		/*
		 * If the count has not breached the low_threshold -> low severity
		 * If the count has breached the high_threshold -> high severity
		 * For all other cases normal severity
		 */
		if (Math.min(percentVariation, LOW_THRESHOLD)
				== percentVariation) {
			return Alert.Severity.LOW;
		} else if (Math.max(percentVariation, HIGH_THRESHOLD) 
				== percentVariation) {
			return Alert.Severity.HIGH;
		} else {
			return Alert.Severity.NORMAL;
		}	
	}


	private StrmNotification fetchNotification(String key, Record notifyRecord) {
		try {
			Pipeline p = pRepo.findOne();
			String grpByAttr = "";
			Set<String> allAttrs = notifyRecord.attr.keySet();
			Iterator itr = allAttrs.iterator();
			while(itr.hasNext()) {
				String attr = (String) itr.next();
				if (attr.equals("start")
						|| attr.equals("end")) 
					continue;
				for (String attr2: p.getNotify()) {
					if (attr.equals(attr2))
						continue;
				}
				grpByAttr = attr;
				break;
			}
			StrmNotification notif = new StrmNotification(
					Long.valueOf((String) notifyRecord.get(grpByAttr).getValue()), 
					(String) notifyRecord.get(p.getNotify().get(0)).getValue(), 
					Long.valueOf((String)notifyRecord.get("start").getValue()), 
					Long.valueOf((String)notifyRecord.get("end").getValue()), 
					grpByAttr, 
					(String) notifyRecord.get(grpByAttr).getValue());
			return notif;
		} catch (Exception e) {
			logger.error("Error translating to StrmNotification");
		}
		return null;
	}


	private void checkPolicyRefresh() {		
		if (lastRefreshTime == 0) {
			refreshPolicy();
		} else if (lastRefreshTime + refreshPeriod > System.currentTimeMillis()) {
			refreshPolicy();
			lastRefreshTime = System.currentTimeMillis();
		} 
	}

	@SuppressWarnings("unchecked")
	private void refreshPolicy() {
		logger.info("Refreshing policy");
		configuredPolicies = repo.findAll();	
		configuredPolicies.forEach(policy -> logger.info("-->" + policy.getPolicyName()));
		
	}
	
}
