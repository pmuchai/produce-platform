<#macro content>
  <p class="prime-root-tagline">${msg("primeRootHeaderCaption")}</p>
  <section class="prime-root-story" aria-labelledby="prime-root-heading">
    <p class="prime-root-eyebrow">${msg("primeRootEyebrow")}</p>
    <h2 id="prime-root-heading">${msg("primeRootHeading")}<br><em>${msg("primeRootHeadingAccent")}</em></h2>
    <p class="prime-root-intro">${msg("primeRootIntro")}</p>
    <figure class="prime-root-illustration">
      <img src="${url.resourcesPath}/img/produce-distribution.svg" width="720" height="520" alt="${msg("primeRootImageDescription")}">
      <figcaption>${msg("primeRootImageCaption")}</figcaption>
    </figure>
  </section>
  <section class="prime-root-card-bottom" aria-label="${msg('primeRootCapabilities')}">
    <p class="prime-root-security">${msg("primeRootSecurity")}</p>
    <div class="prime-root-capabilities">
      <h2>${msg("primeRootCapabilities")}</h2>
      <ul>
        <li><strong>${msg("primeRootReceive")}</strong><span>${msg("primeRootReceiveDescription")}</span></li>
        <li><strong>${msg("primeRootStock")}</strong><span>${msg("primeRootStockDescription")}</span></li>
        <li><strong>${msg("primeRootFreshness")}</strong><span>${msg("primeRootFreshnessDescription")}</span></li>
      </ul>
    </div>
    <p class="prime-root-help">${msg("primeRootHelp")}</p>
  </section>
  <footer class="prime-root-footer"><span>Prime Root</span><span>${msg("primeRootFooter")}</span></footer>
</#macro>
