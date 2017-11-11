require 'json'
require 'set'

$GITPRAPI = "https://api.github.com/repos/%s/SalesforceMobileSDK-android/pulls/%s/files"
$libs = ["SalesforceAnalytics", "SalesforceHybridSDK", "SalesforceReact", "SalesforceSDKCore", "SmartStore", "SmartSync"]

prFilesAPI = $GITPRAPI % [ENV["CIRCLE_PROJECT_USERNAME"], ENV["CIRCLE_PR_NUMBER"]]
pullfiles = `#{curl prFilesAPI}`
prfiles = JSON.parse(pullfiles)

libs = Set.new
for prfile in prfiles
  path = prfile["filename"]
  for lib in $libs
    if path.include? lib
      libs = libs.add(lib)
    end
  end
end
puts libs.to_a().join(", ")
