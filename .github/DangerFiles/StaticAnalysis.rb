#!/usr/bin/env ruby

# Warn when there is a big PR
warn("Big PR, try to keep changes smaller if you can.", sticky: true) if git.lines_of_code > 1000

# Redirect contributors to PR to dev.


# Static Analysis
# List of Android libraries for testing
LIBS = ['SalesforceAnalytics', 'SalesforceSDK', 'SmartStore', 'MobileSync', 'SalesforceHybrid', 'SalesforceReact']

LIBS.each do |lib|
    system("./gradlew libs:#{lib}:lint")

    if File.file?("libs/#{lib}/build/reports/lint-results-debug.xml")
        android_lint.skip_gradle_task = true
        android_lint.report_file = "libs/#{lib}/build/reports/lint-results-debug.xml"
        android_lint.filtering = true
        android_lint.lint(inline_mode: true)
    else
        fail("No Lint Results for #{lib}.")
    end
end