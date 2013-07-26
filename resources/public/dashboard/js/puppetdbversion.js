// Parse URL arguments
function getParameter(paramName) {
  var searchString = window.location.search.substring(1),
      i, val, params = searchString.split("&");

  for (i=0;i<params.length;i++) {
    val = params[i].split("=");
    if (val[0] == paramName) {
      return unescape(val[1]);
    }
  }
  return null;
};

function setVersion() {
  d3.json("/v2/version", function (res) {
    if (res != null && res.version != null) {
      d3.select('#version').html('v' + res.version);
    }
    else {
      d3.select('#version').html('(unknown version)');
    }
  });
};

function checkForUpdates() {
  d3.json("/v2/version/latest", function (res) {
    console.log(res);
    if (res != null && res.newer) {
      d3.select('#latest-version').html('v' + res.version);
      d3.select('#update-link').classed('hidden', false);
      if (res.link) {
        d3.select('#update-link').property('href', res.link);
      }
    }
    else {
      d3.select('#update-link').classed('hidden', true);
    }
  });
};
