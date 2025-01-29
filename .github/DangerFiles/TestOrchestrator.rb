# List of Android libraries for testing
SCHEMES = ['SalesforceAnalytics', 'SalesforceSDK', 'SmartStore', 'MobileSync', 'SalesforceHybrid', 'SalesforceReact']

modifed_libs = Set[]
for file in (git.modified_files + git.added_files);
    scheme = file.split("libs/").last.split("/").first
    if SCHEMES.include?(scheme) 
        modifed_libs.add(scheme)
    end
end

# Set Github Job output so we know which tests to run
json_libs = modifed_libs.map { |l| "'#{l}'"}.join(", ")
`echo "libs=[#{json_libs}]" >> $GITHUB_OUTPUT`