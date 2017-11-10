require 'json'

$GITPRAPI = "https://api.github.com/repos/%s/SalesforceMobileSDK-android/pulls/%s/files"
$LIBS = ["SalesforceAnalytics", "SalesforceHybridSDK", "SalesforceReact", "SalesforceSDKCore", "SmartStore", "SmartSync"]

prFilesAPI = $GITPRAPI % [ENV["CIRCLE_PROJECT_USERNAME"], ENV["CIRCLE_PR_NUMBER"]]
puts "prFIelsAPI: " + prFilesAPI
curlCommand = "curl %s" % [prFilesAPI]
pullfiles = `#{curlCommand}`
prfiles = JSON.parse(pullfiles)

# delete me
puts "PR Files: " + prfiles.to_a().join(",")

libs = Set.new
for prfile in prfiles
  path = prfile["filename"]
  for lib in $LIBS
    if path.include? lib
      libs = libs.add(lib)
    end
  end
end
puts "Libs:" + libs.to_a().join(",")
