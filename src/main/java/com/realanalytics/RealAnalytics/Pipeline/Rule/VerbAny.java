package com.realanalytics.RealAnalytics.Pipeline.Rule;

import java.util.LinkedList;

import com.realanalytics.RealAnalytics.Pipeline.Record;

public class VerbAny extends Verb {

	public VerbAny(String arg) {
		super(arg);
	}

	@Override
	public String call(LinkedList<String> evaluation, String key, Record rec) {
		return key;
	}

}