/**
 * Skills are self-contained sub-packages. Each skill provides its own
 * Config, Service, and Controller. Spring component scanning discovers
 * them automatically. To add a skill, copy its sub-package here and
 * add its properties to application.properties.
 *
 * Convention:
 *   Package:    com.botsfer.skills.&lt;skillname&gt;
 *   Config:     app.skills.&lt;skillname&gt;.enabled = true/false
 *   Endpoints:  /api/skills/&lt;skillname&gt;/*
 */
package com.botsfer.skills;
