package com.cragent.core.agent;

import com.cragent.core.model.ReviewFinding;

import java.util.List;

public interface SubAgent {

    String getDimensionName();

    List<ReviewFinding> review(String diffContent, List<String> changedFilePaths);
}
