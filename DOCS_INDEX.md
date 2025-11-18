# Documentation Index
**Last Updated:** 2025-11-18 04:43

## Quick Reference (Start Here)

- What's working vs broken
- Critical issues summary
- Next action items
- Testing results

- What was done this session
- Key findings and analysis
- Recommendations for next work

## Active Planning Documents

- Critical issues (P0)
- High priority items (P1)
- Medium/Low priority (P2/P3)
- Completed items
- LLM integration roadmap

- Current investigations with time estimates
- Queued tasks awaiting work
- Completed tasks archive
- Implementation notes

## Progress Tracking

- All code changes with timestamps
- Problem analysis and solutions
- Build status for each change
- User feedback integration
- Technical decisions documented

## Project Information

- What the mod does
- Features list
- Installation instructions
- Basic usage

 **changelog.md** - Version history
- Released features
- Bug fixes per version
- Breaking changes

- Archived old version notes

## Special Topics

- Common issues and solutions
- Log analysis tips
- Troubleshooting steps

- How to add custom providers
- Configuration examples

 **CRITICAL_ISSUES_ANALYSIS.md** - Deep dive on major bugs
- Root cause analysis
- Attempted solutions
- Current status

- User testing feedback
- Bug reproduction steps
- Verification status

## Development Workflow

### For New Work Session:

1. **Read CURRENT_STATUS.md** - Understand current state
2. **Check TASK_QUEUE.md** - See what's next
3. **Review latest.log** - Verify actual behavior
4. **Make changes** - Implement fixes
5. **Update gemini_report_3.md** - Document what you did
6. **Update CURRENT_STATUS.md** - Reflect new state

### For Bug Investigation:

1. **Check latest.log** - See what actually happened
2. **Read CRITICAL_ISSUES_ANALYSIS.md** - Context on known issues
3. **Review gemini_report_3.md** - Previous attempts
4. **Investigate code** - Find root cause
5. **Document findings** - Add to appropriate docs

### For Feature Planning:

1. **Add to TODO.md** - List the feature idea
2. **Estimate complexity** - Add time/priority
3. **Move to TASK_QUEUE.md** - When ready to implement
4. **Track in gemini_report_3.md** - As you build it

## Log Files

- Bot behavior
- Errors and warnings
- Command execution
- Task lifecycle

## Configuration Files

echo **GEMINI.md** - AI assistant rules
- How Gemini should work in this repo
- Task scope guidelines
- Documentation requirements
- Code editing expectations

## File Locations

```
AI-Player-checkpoint/
 CURRENT_STATUS. START HEREmd          
 SESSION_SUMMARY_*. Latest session notesmd       
 TODO. Master task listmd                    
 TASK_QUEUE. Active work queuemd             
 gemini_report_3. Detailed change logmd        
 CRITICAL_ISSUES_ANALYSIS.md
 DEBUG_GUIDE.md
 TESTING_FEEDBACK_ANALYSIS.md
 CUSTOM_PROVIDERS.md
 GEMINI. AI assistant rulesmd                 
 README.md
 changelog.md
 logs-prism/
 latest. Runtime logslog               
  Source codesrc/                      
```

## Document Relationships

```
User Testing
    
latest.log (evidence)
    
gemini_report_3.md (detailed analysis)
    
CURRENT_STATUS.md (current state)
    
TODO.md / TASK_QUEUE.md (what to do next)
    
Implementation
    
Back to gemini_report_3.md (document changes)
```

## Maintenance Guidelines

### Keep Updated:
-  gemini_report_3.md - After every code change
-  CURRENT_STATUS.md - After testing/major changes
-  TODO.md - When issues resolved or new issues found
-  TASK_QUEUE.md - When starting/completing tasks

### Update Occasionally:
- - - 
### Archive When Full:
- - 
---

**Quick Navigation:**

- - - - - 
